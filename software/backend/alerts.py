#Runs your rules engine on the server side (fast HR, low SpO2, no movement, low battery) 
# so alerts trigger even if the Android app is closed. Also stores alert history.
from websocket import push_alert
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from sqlalchemy import desc
from pydantic import BaseModel, ConfigDict
from typing import List, Optional
from datetime import datetime
import uuid

from database import get_db, AlertModel, SoldierModel, VitalsModel
from auth import get_current_admin, get_current_operator, require_roles, UserOut
from config import (
    HR_CRITICAL_THRESHOLD,
    SPO2_CRITICAL_THRESHOLD,
    TEMP_CRITICAL_THRESHOLD,
    NO_MOVEMENT_MINUTES
)

router = APIRouter()


# ── Schemas ───────────────────────────────────────────────────────
class AlertOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    soldier_id: str
    soldier_name: str
    soldier_serial: str
    title: str
    severity: str
    message: str
    action_required: bool
    created_at: datetime

class AlertCreate(BaseModel):
    soldier_id: str
    title: str
    severity: str        # "critical" | "warning" | "information"
    message: str
    action_required: bool = False


# ── Helper ────────────────────────────────────────────────────────
def alert_to_out(alert: AlertModel) -> AlertOut:
    soldier = alert.soldier
    return AlertOut(
        id=alert.id,
        soldier_id=alert.soldier_id,
        soldier_name=f"{soldier.rank_title} {soldier.name}",
        soldier_serial=soldier.serial,
        title=alert.title,
        severity=alert.severity,
        message=alert.message,
        action_required=alert.action_required,
        created_at=alert.created_at
    )


# ── Core rules engine ─────────────────────────────────────────────
# Called internally after every vitals POST.
# prev_status: the soldier's status *before* this reading updated it.
#   Rules 6 & 7 only fire when the status just transitioned — not on
#   every subsequent reading while the status stays offline/critical.
# Returns list of alerts that were created.
async def evaluate_and_create_alerts(soldier, hr, spo2, temp, battery, db, prev_status: str = ""):
    created = []
    name = f"{soldier.rank_title} {soldier.name}"
    serial = soldier.serial

    def make_alert(title, severity, message, action_required=False):
        alert = AlertModel(
            id=str(uuid.uuid4()),
            soldier_id=soldier.id,
            title=title,
            severity=severity,
            message=message,
            action_required=action_required
        )
        db.add(alert)
        created.append(alert)

        # Send push notification based on severity
        if severity == "critical":
            send_critical_alert(name, serial, title, message, alert.id)
        elif severity == "warning":
            send_warning_alert(name, serial, title, message, alert.id)
        else:
            send_info_alert(name, serial, title, message, alert.id)

    # ── Rule 1: Very fast heart rate ──────────────────────────────
    if hr is not None and hr > HR_CRITICAL_THRESHOLD:
        make_alert(
            title="Very Fast Heartbeat",
            severity="critical",
            message=f"Heart rate {hr} BPM exceeded threshold of "
                    f"{HR_CRITICAL_THRESHOLD} BPM. "
                    f"Immediate intervention may be required.",
            action_required=True
        )

    # ── Rule 2: Low blood oxygen ──────────────────────────────────
    if spo2 is not None and spo2 < SPO2_CRITICAL_THRESHOLD:
        make_alert(
            title="Low Blood Oxygen",
            severity="critical",
            message=f"SpO2 dropped to {spo2}%, below threshold of "
                    f"{SPO2_CRITICAL_THRESHOLD}%. "
                    f"Immediate intervention may be required.",
            action_required=True
        )

    # ── Rule 3: High body temperature ────────────────────────────
    if temp is not None and temp > TEMP_CRITICAL_THRESHOLD:
        make_alert(
            title="High Body Temperature",
            severity="critical",
            message=f"Body temperature {temp}°F exceeded critical threshold of "
                    f"{TEMP_CRITICAL_THRESHOLD}°F. Possible heat injury.",
            action_required=True
        )

    # ── Rule 4: Low battery ───────────────────────────────────────
    if battery is not None and battery < 20:
        make_alert(
            title="Battery Low",
            severity="warning",
            message=f"Suit battery at {battery}%. "
                    f"Soldier may lose connectivity soon.",
            action_required=False
        )

    # ── Rule 5: Critically low battery ───────────────────────────
    if battery is not None and battery < 5:
        make_alert(
            title="Battery Critical",
            severity="critical",
            message=f"Suit battery at {battery}%. "
                    f"Imminent signal loss. Replace immediately.",
            action_required=True
        )

    # ── Rule 6: Soldier offline / no movement ─────────────────────
    # Gate on status transition: only alert when the soldier just went
    # offline, not on every vitals POST while they stay offline.
    status_just_changed = prev_status != soldier.status
    if soldier.status == "offline" and status_just_changed:
        make_alert(
            title="No Movement Detected",
            severity="warning",
            message=f"No movement detected for {NO_MOVEMENT_MINUTES} minutes. "
                    f"GPS last known position recorded.",
            action_required=False
        )

    # ── Rule 7: Soldier went critical ─────────────────────────────
    # Same gate: alert once on transition, not on every subsequent reading.
    if soldier.status == "critical" and status_just_changed:
        make_alert(
            title="Soldier Status Critical",
            severity="critical",
            message=f"{name} ({serial}) status changed to CRITICAL. "
                    f"Multiple vitals outside safe range.",
            action_required=True
        )

    db.commit()
    for alert in created:
        await push_alert(alert, db)
        
    return created


# ── Routes ────────────────────────────────────────────────────────

# GET /alerts — get all alerts, newest first
@router.get("/", response_model=List[AlertOut])
def get_alerts(
    severity: Optional[str] = None,
    soldier_id: Optional[str] = None,
    limit: int = 100,
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    query = db.query(AlertModel).order_by(desc(AlertModel.created_at))

    if severity:
        query = query.filter(AlertModel.severity == severity)
    if soldier_id:
        query = query.filter(AlertModel.soldier_id == soldier_id)

    alerts = query.limit(limit).all()
    return [alert_to_out(a) for a in alerts]


# GET /alerts/{alert_id} — get one alert
@router.get("/{alert_id}", response_model=AlertOut)
def get_alert(
    alert_id: str,
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    alert = db.query(AlertModel).filter(AlertModel.id == alert_id).first()
    if not alert:
        raise HTTPException(status_code=404, detail="Alert not found")
    return alert_to_out(alert)


# POST /alerts — manually create an alert (admin & commander only)
@router.post("/", response_model=AlertOut)
def create_alert(
    body: AlertCreate,
    db: Session = Depends(get_db),
    user: UserOut = Depends(require_roles("admin", "commander"))
):
    soldier = db.query(SoldierModel).filter(
        SoldierModel.id == body.soldier_id
    ).first()
    if not soldier:
        raise HTTPException(status_code=404, detail="Soldier not found")

    alert = AlertModel(
        id=str(uuid.uuid4()),
        soldier_id=body.soldier_id,
        title=body.title,
        severity=body.severity,
        message=body.message,
        action_required=body.action_required
    )
    db.add(alert)
    db.commit()
    db.refresh(alert)
    return alert_to_out(alert)


# DELETE /alerts/{alert_id} — dismiss a single alert (admin & commander)
@router.delete("/{alert_id}")
def delete_alert(
    alert_id: str,
    db: Session = Depends(get_db),
    user: UserOut = Depends(require_roles("admin", "commander"))
):
    alert = db.query(AlertModel).filter(AlertModel.id == alert_id).first()
    if not alert:
        raise HTTPException(status_code=404, detail="Alert not found")
    db.delete(alert)
    db.commit()
    return {"message": "Alert dismissed"}


# DELETE /alerts — clear all alerts (admin only)
@router.delete("/")
def clear_all_alerts(
    db: Session = Depends(get_db),
    admin: UserOut = Depends(get_current_admin)
):
    db.query(AlertModel).delete()
    db.commit()
    return {"message": "All alerts cleared"}


# GET /alerts/summary/counts — counts per severity for the dashboard badges
@router.get("/summary/counts")
def get_alert_summary(
    db: Session = Depends(get_db),
    current_user: UserOut = Depends(get_current_operator)
):
    total    = db.query(AlertModel).count()
    critical = db.query(AlertModel).filter(AlertModel.severity == "critical").count()
    warning  = db.query(AlertModel).filter(AlertModel.severity == "warning").count()
    info     = db.query(AlertModel).filter(AlertModel.severity == "information").count()

    return {
        "total":       total,
        "critical":    critical,
        "warning":     warning,
        "information": info
    }
from alerts_notifier import (
    send_critical_alert,
    send_warning_alert,
    send_info_alert
)