#Receives GPS coordinates from suits, 
# stores location history per soldier, serves current positions to the Live Map screen.
from websocket import push_location_update
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from sqlalchemy import desc
from pydantic import BaseModel, Field, ConfigDict
from typing import List, Optional
from datetime import datetime, timedelta
import uuid

from database import get_db, LocationModel, SoldierModel
from auth import get_current_admin, get_current_operator, verify_ingestion_auth, UserOut

router = APIRouter()


# ── Schemas ───────────────────────────────────────────────────────
class LocationIn(BaseModel):
    soldier_id: str = Field(..., min_length=1, max_length=100)
    latitude: float = Field(..., ge=-90.0, le=90.0, description="Valid latitude (-90 to +90 degrees)")
    longitude: float = Field(..., ge=-180.0, le=180.0, description="Valid longitude (-180 to +180 degrees)")

class LocationOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    soldier_id: str
    soldier_name: str
    soldier_serial: str
    squad_name: str
    status: str
    latitude: float
    longitude: float
    recorded_at: datetime
    minutes_ago: float

class AllSoldiersMapOut(BaseModel):
    soldier_id: str
    soldier_name: str
    soldier_serial: str
    rank_title: str
    squad_name: str
    status: str
    latitude: Optional[float]
    longitude: Optional[float]
    last_seen: Optional[datetime]
    minutes_ago: Optional[float]
    is_moving: bool


# ── Helper ────────────────────────────────────────────────────────
def location_to_out(loc: LocationModel) -> LocationOut:
    soldier = loc.soldier
    minutes_ago = (
        datetime.utcnow() - loc.recorded_at
    ).total_seconds() / 60

    return LocationOut(
        id=loc.id,
        soldier_id=loc.soldier_id,
        soldier_name=f"{soldier.rank_title} {soldier.name}",
        soldier_serial=soldier.serial,
        squad_name=soldier.squad_rel.name if soldier.squad_rel else "Unknown",
        status=soldier.status,
        latitude=loc.latitude,
        longitude=loc.longitude,
        recorded_at=loc.recorded_at,
        minutes_ago=round(minutes_ago, 1)
    )


# ── Routes ────────────────────────────────────────────────────────

# POST /map/location — suit pushes GPS coordinates
# Called by the suit every N seconds alongside vitals.
@router.post("/location", response_model=LocationOut)
async def receive_location(
    body: LocationIn,
    db: Session = Depends(get_db),
    auth: dict = Depends(verify_ingestion_auth)
):
    soldier = db.query(SoldierModel).filter(
        SoldierModel.id == body.soldier_id
    ).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    # Validate coordinates are real GPS values
    if not (-90 <= body.latitude <= 90):
        raise HTTPException(status_code=400, detail="Invalid latitude")
    if not (-180 <= body.longitude <= 180):
        raise HTTPException(status_code=400, detail="Invalid longitude")

    location = LocationModel(
        soldier_id=body.soldier_id,
        latitude=body.latitude,
        longitude=body.longitude
    )
    db.add(location)
    db.commit()
    db.refresh(location)
    await push_location_update(body.soldier_id, db)
    return location_to_out(location)


# GET /map/live — all soldiers current positions for the live map
# This is the main endpoint the Live Map screen polls.
# Returns latest position for every soldier + movement status.
@router.get("/live", response_model=List[AllSoldiersMapOut])
def get_live_map(
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    soldiers = db.query(SoldierModel).all()
    result = []

    for soldier in soldiers:
        # Get latest location
        latest = db.query(LocationModel)\
            .filter(LocationModel.soldier_id == soldier.id)\
            .order_by(desc(LocationModel.recorded_at))\
            .first()

        # Get second-latest to determine movement
        second_latest = db.query(LocationModel)\
            .filter(LocationModel.soldier_id == soldier.id)\
            .order_by(desc(LocationModel.recorded_at))\
            .offset(1)\
            .first()

        minutes_ago = None
        is_moving = False

        if latest:
            minutes_ago = round(
                (datetime.utcnow() - latest.recorded_at).total_seconds() / 60,
                1
            )

            # Consider moving if position changed in last reading
            if second_latest:
                lat_changed = abs(latest.latitude - second_latest.latitude) > 0.00001
                lng_changed = abs(latest.longitude - second_latest.longitude) > 0.00001
                is_moving = lat_changed or lng_changed

        result.append(AllSoldiersMapOut(
            soldier_id=soldier.id,
            soldier_name=f"{soldier.rank_title} {soldier.name}",
            soldier_serial=soldier.serial,
            rank_title=soldier.rank_title,
            squad_name=soldier.squad_rel.name if soldier.squad_rel else "Unknown",
            status=soldier.status,
            latitude=latest.latitude if latest else None,
            longitude=latest.longitude if latest else None,
            last_seen=latest.recorded_at if latest else None,
            minutes_ago=minutes_ago,
            is_moving=is_moving
        ))

    return result


# GET /map/{soldier_id}/latest — latest position of one soldier
@router.get("/{soldier_id}/latest", response_model=LocationOut)
def get_soldier_location(
    soldier_id: str,
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    soldier = db.query(SoldierModel).filter(
        SoldierModel.id == soldier_id
    ).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    location = db.query(LocationModel)\
        .filter(LocationModel.soldier_id == soldier_id)\
        .order_by(desc(LocationModel.recorded_at))\
        .first()

    if not location:
        raise HTTPException(
            status_code=404,
            detail="No location data found for this soldier"
        )

    return location_to_out(location)


# GET /map/{soldier_id}/trail — last N positions (movement trail)
# Used to draw a path line on the map showing where the soldier moved.
@router.get("/{soldier_id}/trail", response_model=List[LocationOut])
def get_soldier_trail(
    soldier_id: str,
    limit: int = 20,
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    soldier = db.query(SoldierModel).filter(
        SoldierModel.id == soldier_id
    ).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    locations = db.query(LocationModel)\
        .filter(LocationModel.soldier_id == soldier_id)\
        .order_by(desc(LocationModel.recorded_at))\
        .limit(limit)\
        .all()

    return [location_to_out(loc) for loc in locations]


# GET /map/squad/{squad_id} — all soldiers in one squad on the map
@router.get("/squad/{squad_id}", response_model=List[AllSoldiersMapOut])
def get_squad_map(
    squad_id: str,
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    soldiers = db.query(SoldierModel)\
        .filter(SoldierModel.squad_id == squad_id)\
        .all()

    if not soldiers:
        raise HTTPException(status_code=404, detail="No soldiers found in this squad")

    result = []
    for soldier in soldiers:
        latest = db.query(LocationModel)\
            .filter(LocationModel.soldier_id == soldier.id)\
            .order_by(desc(LocationModel.recorded_at))\
            .first()

        second_latest = db.query(LocationModel)\
            .filter(LocationModel.soldier_id == soldier.id)\
            .order_by(desc(LocationModel.recorded_at))\
            .offset(1)\
            .first()

        minutes_ago = None
        is_moving = False

        if latest:
            minutes_ago = round(
                (datetime.utcnow() - latest.recorded_at).total_seconds() / 60,
                1
            )
            # Consider moving if position changed in last reading
            if second_latest:
                lat_changed = abs(latest.latitude - second_latest.latitude) > 0.00001
                lng_changed = abs(latest.longitude - second_latest.longitude) > 0.00001
                is_moving = lat_changed or lng_changed

        result.append(AllSoldiersMapOut(
            soldier_id=soldier.id,
            soldier_name=f"{soldier.rank_title} {soldier.name}",
            soldier_serial=soldier.serial,
            rank_title=soldier.rank_title,
            squad_name=soldier.squad_rel.name if soldier.squad_rel else "Unknown",
            status=soldier.status,
            latitude=latest.latitude if latest else None,
            longitude=latest.longitude if latest else None,
            last_seen=latest.recorded_at if latest else None,
            minutes_ago=minutes_ago,
            is_moving=is_moving
        ))

    return result


# DELETE /map/{soldier_id}/history — clear location history
# Useful for privacy or storage management (admin only)
@router.delete("/{soldier_id}/history")
def clear_location_history(
    soldier_id: str,
    db: Session = Depends(get_db),
    admin=Depends(get_current_admin)
):
    soldier = db.query(SoldierModel).filter(
        SoldierModel.id == soldier_id
    ).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    deleted = db.query(LocationModel)\
        .filter(LocationModel.soldier_id == soldier_id)\
        .delete()

    db.commit()
    return {
        "message": f"Cleared {deleted} location records for {soldier.name}"
    }