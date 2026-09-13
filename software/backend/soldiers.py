#CRUD operations for soldiers — create, read, update, delete soldiers and their data 
# (name, rank, blood group, squad, role, photo). 
# This replaces your current hardcoded SoldierState list.
from s3helper import upload_photo, delete_photo, get_presigned_url
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, Form, status
from sqlalchemy.orm import Session
from pydantic import BaseModel, ConfigDict
from typing import List, Optional
from datetime import datetime
import uuid
import os

from database import get_db, SoldierModel, Squad, SuitConfigModel
from auth import get_current_admin, get_current_operator, UserOut

router = APIRouter()


# ── Schemas ───────────────────────────────────────────────────────
class SoldierCreate(BaseModel):
    name: str
    rank_title: str
    rank_order: int
    serial: str
    squad_id: str
    role: str
    blood_group: str = "O+"
    status: str = "stable"

class SoldierUpdate(BaseModel):
    name: Optional[str] = None
    rank_title: Optional[str] = None
    rank_order: Optional[int] = None
    serial: Optional[str] = None
    squad_id: Optional[str] = None
    role: Optional[str] = None
    blood_group: Optional[str] = None
    status: Optional[str] = None

class SoldierOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    rank_title: str
    rank_order: int
    serial: str
    squad_id: Optional[str]
    squad_name: Optional[str]
    role: str
    blood_group: str
    status: str
    photo_url: Optional[str]
    created_at: datetime


# ── Helper ────────────────────────────────────────────────────────
def soldier_to_out(soldier: SoldierModel) -> SoldierOut:
    # Generate a fresh presigned URL if soldier has a photo
    photo_url = None
    if soldier.photo_path:  # photo_path now stores the S3 key
        try:
            photo_url = get_presigned_url(soldier.photo_path)
        except Exception:
            photo_url = None

    return SoldierOut(
        id=soldier.id,
        name=soldier.name,
        rank_title=soldier.rank_title,
        rank_order=soldier.rank_order,
        serial=soldier.serial,
        squad_id=soldier.squad_id,
        squad_name=soldier.squad_rel.name if soldier.squad_rel else None,
        role=soldier.role,
        blood_group=soldier.blood_group,
        status=soldier.status,
        photo_url=photo_url,
        created_at=soldier.created_at
    )


# ── Routes ────────────────────────────────────────────────────────

# GET /soldiers — get all soldiers, sorted by rank
@router.get("/", response_model=List[SoldierOut])
def get_soldiers(
    squad_id: Optional[str] = None,
    status: Optional[str] = None,
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    query = db.query(SoldierModel)

    if squad_id:
        query = query.filter(SoldierModel.squad_id == squad_id)
    if status:
        query = query.filter(SoldierModel.status == status)

    soldiers = query.order_by(SoldierModel.rank_order).all()
    return [soldier_to_out(s) for s in soldiers]


# GET /soldiers/{soldier_id} — get one soldier
@router.get("/{soldier_id}", response_model=SoldierOut)
def get_soldier(
    soldier_id: str,
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    soldier = db.query(SoldierModel).filter(SoldierModel.id == soldier_id).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")
    return soldier_to_out(soldier)


# POST /soldiers — create soldier
@router.post("/", response_model=SoldierOut)
def create_soldier(
    body: SoldierCreate,
    db: Session = Depends(get_db),
    admin=Depends(get_current_admin)
):
    # Check serial is unique
    if db.query(SoldierModel).filter(SoldierModel.serial == body.serial).first():
        raise HTTPException(status_code=400, detail="Serial number already exists")

    # Check squad exists
    if not db.query(Squad).filter(Squad.id == body.squad_id).first():
        raise HTTPException(status_code=404, detail="Squad not found")

    soldier = SoldierModel(
        id=str(uuid.uuid4()),
        name=body.name.strip(),
        rank_title=body.rank_title.strip(),
        rank_order=body.rank_order,
        serial=body.serial.strip().upper(),
        squad_id=body.squad_id,
        role=body.role.strip(),
        blood_group=body.blood_group,
        status=body.status
    )
    db.add(soldier)

    # Create default suit config for this soldier
    config = SuitConfigModel(soldier_id=soldier.id)
    db.add(config)

    db.commit()
    db.refresh(soldier)
    return soldier_to_out(soldier)


# PUT /soldiers/{soldier_id} — update soldier details
@router.put("/{soldier_id}", response_model=SoldierOut)
def update_soldier(
    soldier_id: str,
    body: SoldierUpdate,
    db: Session = Depends(get_db),
    admin=Depends(get_current_admin)
):
    soldier = db.query(SoldierModel).filter(SoldierModel.id == soldier_id).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    if body.name is not None:        soldier.name = body.name.strip()
    if body.rank_title is not None:  soldier.rank_title = body.rank_title.strip()
    if body.rank_order is not None:  soldier.rank_order = body.rank_order
    if body.role is not None:        soldier.role = body.role.strip()
    if body.blood_group is not None: soldier.blood_group = body.blood_group
    if body.status is not None:      soldier.status = body.status

    if body.serial is not None:
        duplicate = db.query(SoldierModel).filter(
            SoldierModel.serial == body.serial,
            SoldierModel.id != soldier_id
        ).first()
        if duplicate:
            raise HTTPException(status_code=400, detail="Serial number already exists")
        soldier.serial = body.serial.strip().upper()

    if body.squad_id is not None:
        if not db.query(Squad).filter(Squad.id == body.squad_id).first():
            raise HTTPException(status_code=404, detail="Squad not found")
        soldier.squad_id = body.squad_id

    db.commit()
    db.refresh(soldier)
    return soldier_to_out(soldier)


# DELETE /soldiers/{soldier_id} — remove soldier
@router.delete("/{soldier_id}")
def delete_soldier(
    soldier_id: str,
    db: Session = Depends(get_db),
    admin=Depends(get_current_admin)
):
    soldier = db.query(SoldierModel).filter(SoldierModel.id == soldier_id).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    # Delete photo from S3 if the soldier has one.
    # photo_path stores an S3 key (e.g. "soldiers/<id>/photo.jpeg"),
    # not a local filesystem path — use delete_photo(), not os.remove().
    if soldier.photo_path:
        try:
            delete_photo(soldier.photo_path)
        except Exception:
            pass  # already gone from S3, continue with DB deletion

    db.delete(soldier)
    db.commit()
    return {"message": f"Soldier {soldier.serial} removed"}


# POST /soldiers/{soldier_id}/photo — upload profile photo
@router.post("/{soldier_id}/photo")
async def upload_soldier_photo(
    soldier_id: str,
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    admin=Depends(get_current_admin)
):
    soldier = db.query(SoldierModel).filter(
        SoldierModel.id == soldier_id
    ).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    # Only allow images
    if not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="File must be an image")

    # Delete old photo from S3 if exists
    if soldier.photo_path:
        try:
            delete_photo(soldier.photo_path)
        except Exception:
            pass  # old photo gone already, continue

    # Read file bytes and upload to S3
    file_bytes = await file.read()
    try:
        s3_key = upload_photo(file_bytes, file.content_type, soldier_id)
    except ValueError as ve:
        raise HTTPException(status_code=400, detail=str(ve))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

    # Save S3 key to DB (not the URL — URLs expire, keys don't)
    soldier.photo_path = s3_key
    db.commit()

    # Return a fresh presigned URL
    photo_url = get_presigned_url(s3_key)
    return {"message": "Photo uploaded to S3", "photo_url": photo_url}

# GET /soldiers/photo/{soldier_id} — serve the photo
@router.get("/photo/{soldier_id}")
def get_photo(
    soldier_id: str,
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    soldier = db.query(SoldierModel).filter(
        SoldierModel.id == soldier_id
    ).first()
    if not soldier or not soldier.photo_path:
        raise HTTPException(status_code=404, detail="No photo found")

    try:
        url = get_presigned_url(soldier.photo_path)
        return {"photo_url": url}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))