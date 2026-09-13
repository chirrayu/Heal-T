from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from pydantic import BaseModel, ConfigDict
from typing import List
from datetime import datetime
import uuid

from database import get_db, Squad
from auth import get_current_admin, get_current_operator, UserOut

router = APIRouter()


# ── Schemas ───────────────────────────────────────────────────────
class SquadCreate(BaseModel):
    name: str

class SquadUpdate(BaseModel):
    name: str

class SquadOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    created_at: datetime
    soldier_count: int = 0


# ── Routes ────────────────────────────────────────────────────────

# GET /squads — get all squads
@router.get("/", response_model=List[SquadOut])
def get_squads(
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    squads = db.query(Squad).all()
    result = []
    for squad in squads:
        result.append(SquadOut(
            id=squad.id,
            name=squad.name,
            created_at=squad.created_at,
            soldier_count=len(squad.soldiers)
        ))
    return result


# POST /squads — create a new squad
@router.post("/", response_model=SquadOut)
def create_squad(
    body: SquadCreate,
    db: Session = Depends(get_db),
    admin=Depends(get_current_admin)
):
    # Check for duplicate name
    existing = db.query(Squad).filter(Squad.name == body.name).first()
    if existing:
        raise HTTPException(status_code=400, detail="Squad name already exists")

    squad = Squad(
        id=str(uuid.uuid4()),
        name=body.name.strip()
    )
    db.add(squad)
    db.commit()
    db.refresh(squad)

    return SquadOut(
        id=squad.id,
        name=squad.name,
        created_at=squad.created_at,
        soldier_count=0
    )


# PUT /squads/{squad_id} — rename a squad
@router.put("/{squad_id}", response_model=SquadOut)
def update_squad(
    squad_id: str,
    body: SquadUpdate,
    db: Session = Depends(get_db),
    admin=Depends(get_current_admin)
):
    squad = db.query(Squad).filter(Squad.id == squad_id).first()
    if not squad:
        raise HTTPException(status_code=404, detail="Squad not found")

    # Check duplicate name
    duplicate = db.query(Squad).filter(
        Squad.name == body.name,
        Squad.id != squad_id
    ).first()
    if duplicate:
        raise HTTPException(status_code=400, detail="Squad name already exists")

    squad.name = body.name.strip()
    db.commit()
    db.refresh(squad)

    return SquadOut(
        id=squad.id,
        name=squad.name,
        created_at=squad.created_at,
        soldier_count=len(squad.soldiers)
    )


# DELETE /squads/{squad_id} — delete a squad
@router.delete("/{squad_id}")
def delete_squad(
    squad_id: str,
    db: Session = Depends(get_db),
    admin=Depends(get_current_admin)
):
    squad = db.query(Squad).filter(Squad.id == squad_id).first()
    if not squad:
        raise HTTPException(status_code=404, detail="Squad not found")

    if len(squad.soldiers) > 0:
        raise HTTPException(
            status_code=400,
            detail=f"Cannot delete squad — {len(squad.soldiers)} soldier(s) still assigned to it"
        )

    db.delete(squad)
    db.commit()
    return {"message": f"Squad '{squad.name}' deleted"}