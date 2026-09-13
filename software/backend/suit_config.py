from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from pydantic import BaseModel, ConfigDict
from typing import Optional, List
from datetime import datetime

from database import get_db, SuitConfigModel, SoldierModel
from auth import get_current_admin, get_current_operator, require_roles, verify_ingestion_auth, UserOut

router = APIRouter()


# ── Schemas ───────────────────────────────────────────────────────
class SuitConfigOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    soldier_id: str
    soldier_name: str
    soldier_serial: str
    hr_sensor: bool
    spo2_sensor: bool
    temp_sensor: bool
    accelerometer: bool
    gps_enabled: bool
    sampling_rate_secs: int
    wifi_enabled: bool
    mesh_enabled: bool
    radio_gateway: bool
    emergency_mode: bool
    updated_at: datetime

class SuitConfigUpdate(BaseModel):
    hr_sensor: Optional[bool] = None
    spo2_sensor: Optional[bool] = None
    temp_sensor: Optional[bool] = None
    accelerometer: Optional[bool] = None
    gps_enabled: Optional[bool] = None
    sampling_rate_secs: Optional[int] = None
    wifi_enabled: Optional[bool] = None
    mesh_enabled: Optional[bool] = None
    radio_gateway: Optional[bool] = None
    emergency_mode: Optional[bool] = None

class EmergencyModeIn(BaseModel):
    enabled: bool


# ── Helper ────────────────────────────────────────────────────────
def config_to_out(config: SuitConfigModel) -> SuitConfigOut:
    soldier = config.soldier
    return SuitConfigOut(
        soldier_id=config.soldier_id,
        soldier_name=f"{soldier.rank_title} {soldier.name}",
        soldier_serial=soldier.serial,
        hr_sensor=config.hr_sensor,
        spo2_sensor=config.spo2_sensor,
        temp_sensor=config.temp_sensor,
        accelerometer=config.accelerometer,
        gps_enabled=config.gps_enabled,
        sampling_rate_secs=config.sampling_rate_secs,
        wifi_enabled=config.wifi_enabled,
        mesh_enabled=config.mesh_enabled,
        radio_gateway=config.radio_gateway,
        emergency_mode=config.emergency_mode,
        updated_at=config.updated_at
    )


# ── Routes ────────────────────────────────────────────────────────

# GET /suit/{soldier_id} — get suit config for one soldier
@router.get("/{soldier_id}", response_model=SuitConfigOut)
def get_suit_config(
    soldier_id: str,
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    config = db.query(SuitConfigModel).filter(
        SuitConfigModel.soldier_id == soldier_id
    ).first()

    if not config:
        raise HTTPException(
            status_code=404,
            detail="No suit config found for this soldier"
        )

    return config_to_out(config)


# PUT /suit/{soldier_id} — update suit config (admin only)
@router.put("/{soldier_id}", response_model=SuitConfigOut)
def update_suit_config(
    soldier_id: str,
    body: SuitConfigUpdate,
    db: Session = Depends(get_db),
    admin: UserOut = Depends(get_current_admin)
):
    soldier = db.query(SoldierModel).filter(
        SoldierModel.id == soldier_id
    ).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    config = db.query(SuitConfigModel).filter(
        SuitConfigModel.soldier_id == soldier_id
    ).first()

    # Create default config if it doesn't exist yet
    if not config:
        config = SuitConfigModel(soldier_id=soldier_id)
        db.add(config)

    # Apply only fields that were provided
    if body.hr_sensor is not None:
        config.hr_sensor = body.hr_sensor
        if not body.hr_sensor:
            soldier.status = "serious" if soldier.status == "stable" else soldier.status

    if body.spo2_sensor is not None:
        config.spo2_sensor = body.spo2_sensor

    if body.temp_sensor is not None:
        config.temp_sensor = body.temp_sensor

    if body.accelerometer is not None:
        config.accelerometer = body.accelerometer

    if body.gps_enabled is not None:
        config.gps_enabled = body.gps_enabled

    if body.sampling_rate_secs is not None:
        if body.sampling_rate_secs < 1:
            raise HTTPException(
                status_code=400,
                detail="Sampling rate must be at least 1 second"
            )
        config.sampling_rate_secs = body.sampling_rate_secs

    if body.wifi_enabled is not None:
        config.wifi_enabled = body.wifi_enabled

    if body.mesh_enabled is not None:
        config.mesh_enabled = body.mesh_enabled

    if body.radio_gateway is not None:
        config.radio_gateway = body.radio_gateway

    if body.emergency_mode is not None:
        config.emergency_mode = body.emergency_mode

    config.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(config)
    return config_to_out(config)


# POST /suit/{soldier_id}/emergency — toggle emergency mode (admin & commander)
@router.post("/{soldier_id}/emergency", response_model=SuitConfigOut)
async def toggle_emergency_mode(
    soldier_id: str,
    body: EmergencyModeIn,
    db: Session = Depends(get_db),
    user: UserOut = Depends(require_roles("admin", "commander"))
):
    from alerts import evaluate_and_create_alerts

    soldier = db.query(SoldierModel).filter(
        SoldierModel.id == soldier_id
    ).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    config = db.query(SuitConfigModel).filter(
        SuitConfigModel.soldier_id == soldier_id
    ).first()
    if not config:
        config = SuitConfigModel(soldier_id=soldier_id)
        db.add(config)

    config.emergency_mode = body.enabled
    config.updated_at = datetime.utcnow()

    if body.enabled:
        prev_status = soldier.status
        soldier.status = "critical"
        db.commit()
        await evaluate_and_create_alerts(
            soldier=soldier,
            hr=None,
            spo2=None,
            temp=None,
            battery=None,
            db=db,
            prev_status=prev_status
        )
    else:
        db.commit()

    db.refresh(config)
    return config_to_out(config)


# POST /suit/{soldier_id}/reset — reset config to factory defaults (admin only)
@router.post("/{soldier_id}/reset", response_model=SuitConfigOut)
def reset_suit_config(
    soldier_id: str,
    db: Session = Depends(get_db),
    admin: UserOut = Depends(get_current_admin)
):
    config = db.query(SuitConfigModel).filter(
        SuitConfigModel.soldier_id == soldier_id
    ).first()

    if not config:
        raise HTTPException(
            status_code=404,
            detail="No suit config found for this soldier"
        )

    # Reset all fields to defaults
    config.hr_sensor          = True
    config.spo2_sensor        = True
    config.temp_sensor        = True
    config.accelerometer      = True
    config.gps_enabled        = True
    config.sampling_rate_secs = 5
    config.wifi_enabled       = True
    config.mesh_enabled       = True
    config.radio_gateway      = False
    config.emergency_mode     = False
    config.updated_at         = datetime.utcnow()

    db.commit()
    db.refresh(config)
    return config_to_out(config)


# GET /suit/{soldier_id}/commands — what the suit should do right now
# The suit hardware or operator calls this endpoint to check current config.
@router.get("/{soldier_id}/commands")
def get_suit_commands(
    soldier_id: str,
    db: Session = Depends(get_db),
    auth: dict = Depends(verify_ingestion_auth)
):
    config = db.query(SuitConfigModel).filter(
        SuitConfigModel.soldier_id == soldier_id
    ).first()

    if not config:
        raise HTTPException(
            status_code=404,
            detail="No config found"
        )

    return {
        "soldier_id": soldier_id,
        "commands": {
            "sensors": {
                "hr":            config.hr_sensor,
                "spo2":          config.spo2_sensor,
                "temperature":   config.temp_sensor,
                "accelerometer": config.accelerometer,
                "gps":           config.gps_enabled
            },
            "sampling_rate_secs": config.sampling_rate_secs,
            "communication": {
                "wifi":          config.wifi_enabled,
                "mesh_network":  config.mesh_enabled,
                "radio_gateway": config.radio_gateway
            },
            "emergency_mode": config.emergency_mode
        },
        "generated_at": datetime.utcnow().isoformat()
    }