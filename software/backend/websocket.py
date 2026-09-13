# WebSocket server so the Android app gets live updates 
# (vitals, alerts, map positions) pushed to it instead of polling every few seconds.
# Also handles direct ESP32 Wi-Fi connections and BLE telemetry forwarding.

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Depends
from sqlalchemy.orm import Session
from typing import Dict, List, Optional
import asyncio
import json
import hmac
from datetime import datetime

from database import get_db, SessionLocal, SoldierModel, VitalsModel, LocationModel, AlertModel, ESP32DeviceModel
from sqlalchemy import desc
from auth import decode_token
from config import DEVICE_AUTH_TOKEN

# Imported lazily inside handlers to avoid circular imports at module load time.
# (vitals imports websocket, so we import vitals only when needed.)

router = APIRouter()


# ── ESP32 Device Manager ──────────────────────────────────────────
# Tracks active ESP32 WebSocket connections in memory and syncs state to the DB.
class ESP32Manager:
    def __init__(self):
        # device_id -> {"mode": "wifi"|"ble", "last_seen": datetime, "battery": int, "ws": Optional[WebSocket], "status": str}
        self.devices: Dict[str, dict] = {}

    def register_device(self, device_id: str, mode: str = "wifi", ws: Optional[WebSocket] = None):
        self.devices[device_id] = {
            "mode": mode,
            "last_seen": datetime.utcnow(),
            "battery": 100,
            "ws": ws,
            "status": "online"
        }
        self._sync_to_db(device_id, mode)

    def update_device(self, device_id: str, data: dict):
        if device_id in self.devices:
            self.devices[device_id]["last_seen"] = datetime.utcnow()
            if "battery" in data:
                self.devices[device_id]["battery"] = data["battery"]
            if "mode" in data:
                self.devices[device_id]["mode"] = data["mode"]
        else:
            self.register_device(device_id, mode=data.get("mode", "unknown"))
            self.update_device(device_id, data)

    def switch_mode(self, device_id: str, new_mode: str):
        if device_id in self.devices:
            self.devices[device_id]["mode"] = new_mode
            self.devices[device_id]["last_seen"] = datetime.utcnow()
            self._sync_to_db(device_id, new_mode)
            return True
        return False

    def mark_offline(self, device_id: str):
        if device_id in self.devices:
            self.devices[device_id]["status"] = "offline"
            self.devices[device_id]["ws"] = None
            self._sync_to_db(device_id, self.devices[device_id]["mode"], status="offline")

    def get_all_devices(self):
        return self.devices

    def _sync_to_db(self, device_id: str, mode: str, status: str = "online"):
        """Persists ESP32 device state to the database."""
        db = SessionLocal()
        try:
            device = db.query(ESP32DeviceModel).filter(ESP32DeviceModel.device_id == device_id).first()
            if not device:
                device = ESP32DeviceModel(device_id=device_id, connection_mode=mode, status=status)
                db.add(device)
            else:
                device.connection_mode = mode
                device.status = status
                device.last_seen = datetime.utcnow()
            db.commit()
        except Exception as e:
            print(f"DB sync error for ESP32 {device_id}: {e}")
            db.rollback()
        finally:
            db.close()

esp32_manager = ESP32Manager()


# ── Connection manager (Android App Clients) ──────────────────────
class ConnectionManager:
    def __init__(self):
        self.active: List[WebSocket] = []
        self.subscriptions: Dict[str, List[WebSocket]] = {
            "vitals": [],
            "alerts": [],
            "map": [],
            "all": []
        }

    async def connect(self, websocket: WebSocket, feed: str = "all"):
        await websocket.accept()
        self.active.append(websocket)
        if feed in self.subscriptions:
            self.subscriptions[feed].append(websocket)
        else:
            self.subscriptions["all"].append(websocket)
        print(f"WS client connected — feed: {feed} | total: {len(self.active)}")

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active:
            self.active.remove(websocket)
        for feed_list in self.subscriptions.values():
            if websocket in feed_list:
                feed_list.remove(websocket)
        print(f"WS client disconnected | total: {len(self.active)}")

    async def push(self, message: dict, feed: str = "all"):
        targets = self.subscriptions.get(feed, []) + self.subscriptions["all"]
        targets = list(set(targets))
        dead = []
        for ws in targets:
            try:
                await ws.send_text(json.dumps(message, default=str))
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)

    async def push_to_all(self, message: dict):
        dead = []
        for ws in self.active:
            try:
                await ws.send_text(json.dumps(message, default=str))
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)

manager = ConnectionManager()


# ── Message builders ──────────────────────────────────────────────
def build_vitals_message(soldier_id: str, db: Session) -> dict:
    vitals = db.query(VitalsModel)\
        .filter(VitalsModel.soldier_id == soldier_id)\
        .order_by(desc(VitalsModel.recorded_at))\
        .first()
    soldier = db.query(SoldierModel).filter(SoldierModel.id == soldier_id).first()
    if not vitals or not soldier:
        return {}
    return {
        "type": "vitals_update",
        "soldier_id": soldier_id,
        "soldier_name": f"{soldier.rank_title} {soldier.name}",
        "serial": soldier.serial,
        "status": soldier.status,
        "hr": vitals.hr,
        "spo2": vitals.spo2,
        "temp": vitals.temp,
        "battery": vitals.battery,
        "timestamp": vitals.recorded_at.isoformat()
    }

def build_location_message(soldier_id: str, db: Session) -> dict:
    location = db.query(LocationModel)\
        .filter(LocationModel.soldier_id == soldier_id)\
        .order_by(desc(LocationModel.recorded_at))\
        .first()
    soldier = db.query(SoldierModel).filter(SoldierModel.id == soldier_id).first()
    if not location or not soldier:
        return {}
    return {
        "type": "location_update",
        "soldier_id": soldier_id,
        "soldier_name": f"{soldier.rank_title} {soldier.name}",
        "serial": soldier.serial,
        "status": soldier.status,
        "latitude": location.latitude,
        "longitude": location.longitude,
        "timestamp": location.recorded_at.isoformat()
    }

def build_alert_message(alert: AlertModel, db: Session) -> dict:
    soldier = db.query(SoldierModel).filter(SoldierModel.id == alert.soldier_id).first()
    return {
        "type": "new_alert",
        "alert_id": alert.id,
        "soldier_id": alert.soldier_id,
        "soldier_name": f"{soldier.rank_title} {soldier.name}" if soldier else "Unknown",
        "serial": soldier.serial if soldier else "",
        "title": alert.title,
        "severity": alert.severity,
        "message": alert.message,
        "action_required": alert.action_required,
        "timestamp": alert.created_at.isoformat()
    }

def build_full_snapshot(db: Session) -> dict:
    soldiers = db.query(SoldierModel).all()
    soldier_data = []
    for soldier in soldiers:
        vitals = db.query(VitalsModel)\
            .filter(VitalsModel.soldier_id == soldier.id)\
            .order_by(desc(VitalsModel.recorded_at))\
            .first()
        location = db.query(LocationModel)\
            .filter(LocationModel.soldier_id == soldier.id)\
            .order_by(desc(LocationModel.recorded_at))\
            .first()
        soldier_data.append({
            "soldier_id": soldier.id,
            "name": f"{soldier.rank_title} {soldier.name}",
            "rank_title": soldier.rank_title,
            "rank_order": soldier.rank_order,
            "role": soldier.role,
            "blood_group": soldier.blood_group,
            "serial": soldier.serial,
            "squad": soldier.squad_rel.name if soldier.squad_rel else None,
            "status": soldier.status,
            "hr": vitals.hr if vitals else None,
            "spo2": vitals.spo2 if vitals else None,
            "temp": vitals.temp if vitals else None,
            "battery": vitals.battery if vitals else None,
            "latitude": location.latitude if location else None,
            "longitude": location.longitude if location else None,
        })

    recent_alerts = db.query(AlertModel).order_by(desc(AlertModel.created_at)).limit(20).all()

    # Include ESP32 device statuses in the snapshot
    esp32_devices_status = {}
    for dev_id, info in esp32_manager.get_all_devices().items():
        esp32_devices_status[dev_id] = {
            "mode": info["mode"],
            "status": info["status"],
            "battery": info["battery"],
            "last_seen": info["last_seen"].isoformat()
        }

    return {
        "type": "snapshot",
        "soldiers": soldier_data,
        "alert_counts": {
            "critical": db.query(AlertModel).filter(AlertModel.severity == "critical").count(),
            "warning":  db.query(AlertModel).filter(AlertModel.severity == "warning").count(),
            "total":    db.query(AlertModel).count()
        },
        "esp32_devices": esp32_devices_status,
        "timestamp": datetime.utcnow().isoformat()
    }


# ── WebSocket endpoints ───────────────────────────────────────────

@router.websocket("/connect")
async def websocket_connect(
    websocket: WebSocket,
    feed: str = "all",
    token: Optional[str] = None
):
    # Authenticate token from query param or initial handshake
    authenticated_user = None
    if token:
        try:
            authenticated_user = decode_token(token)
        except Exception:
            await websocket.close(code=1008, reason="Invalid authentication token")
            return

    # If no query token was provided, accept and await auth message within 5 seconds
    if not authenticated_user:
        await websocket.accept()
        try:
            auth_msg_raw = await asyncio.wait_for(websocket.receive_text(), timeout=5.0)
            auth_msg = json.loads(auth_msg_raw)
            if auth_msg.get("type") == "auth" and auth_msg.get("token"):
                authenticated_user = decode_token(auth_msg["token"])
                await websocket.send_text(json.dumps({"type": "auth_success", "username": authenticated_user.username}))
            else:
                await websocket.send_text(json.dumps({"type": "auth_error", "message": "Authentication required"}))
                await websocket.close(code=1008, reason="Authentication required")
                return
        except Exception:
            try:
                await websocket.close(code=1008, reason="Authentication timeout or invalid token")
            except Exception:
                pass
            return
    else:
        await websocket.accept()

    db = next(get_db())
    manager.active.append(websocket)
    if feed in manager.subscriptions:
        manager.subscriptions[feed].append(websocket)
    else:
        manager.subscriptions["all"].append(websocket)
    print(f"WS client connected ({authenticated_user.username}) — feed: {feed} | total: {len(manager.active)}")

    try:
        snapshot = build_full_snapshot(db)
        await websocket.send_text(json.dumps(snapshot, default=str))

        while True:
            try:
                data = await asyncio.wait_for(websocket.receive_text(), timeout=30.0)
                msg = json.loads(data)

                if msg.get("type") == "ping":
                    await websocket.send_text(json.dumps({"type": "pong", "timestamp": datetime.utcnow().isoformat()}))

                elif msg.get("type") == "request_snapshot":
                    snapshot = build_full_snapshot(db)
                    await websocket.send_text(json.dumps(snapshot, default=str))

                # Android app forwards BLE telemetry from ESP32
                elif msg.get("type") == "esp32_ble_telemetry":
                    device_id = msg.get("device_id")
                    data = msg.get("data", {})
                    if device_id:
                        esp32_manager.update_device(device_id, {**data, "mode": "ble"})
                        # Save BLE telemetry to DB and trigger alert rules engine
                        soldier_id = data.get("soldier_id")
                        if soldier_id:
                            try:
                                from vitals import process_vitals_reading, VitalsIn
                                vitals_body = VitalsIn(
                                    soldier_id=soldier_id,
                                    hr=data.get("hr"),
                                    spo2=data.get("spo2"),
                                    temp=data.get("temp"),
                                    battery=data.get("battery"),
                                    device_id=device_id,
                                    connection_type="ble",
                                )
                                await process_vitals_reading(vitals_body, db)
                            except Exception as e:
                                print(f"BLE vitals save error: {e}")
                        await websocket.send_text(json.dumps({"type": "ack", "status": "ble_data_received"}))

                # Admin/App requests ESP32 to switch connection mode
                elif msg.get("type") == "switch_esp32_mode":
                    device_id = msg.get("device_id")
                    new_mode = msg.get("mode")  # "wifi" or "ble"
                    if device_id and new_mode:
                        esp32_manager.switch_mode(device_id, new_mode)
                        
                        # If ESP32 is currently connected via Wi-Fi WebSocket, tell it to switch
                        dev = esp32_manager.devices.get(device_id)
                        if dev and dev["mode"] == "wifi" and dev["ws"]:
                            try:
                                await dev["ws"].send_text(json.dumps({"type": "command", "action": "switch_to_ble"}))
                            except Exception:
                                pass  # ESP32 might have disconnected
                        
                        await websocket.send_text(json.dumps({
                            "type": "mode_switch_initiated", 
                            "device_id": device_id, 
                            "mode": new_mode
                        }))

            except asyncio.TimeoutError:
                await websocket.send_text(json.dumps({"type": "heartbeat", "timestamp": datetime.utcnow().isoformat()}))

    except WebSocketDisconnect:
        manager.disconnect(websocket)
    except Exception as e:
        print(f"WebSocket error: {e}")
        manager.disconnect(websocket)
    finally:
        db.close()


# Dedicated WebSocket endpoint for ESP32 devices (Wi-Fi Mode)
@router.websocket("/esp32/{device_id}")
async def esp32_websocket_connect(
    websocket: WebSocket,
    device_id: str,
    device_token: Optional[str] = None,
    token: Optional[str] = None
):
    """Direct WebSocket connection for ESP32 devices when in Wi-Fi mode with token authentication."""
    # Check device authentication
    authenticated = False
    if device_token and hmac.compare_digest(device_token, DEVICE_AUTH_TOKEN):
        authenticated = True
    elif token:
        try:
            decode_token(token)
            authenticated = True
        except Exception:
            pass

    if not authenticated:
        # Give device 5 seconds to authenticate via initial JSON payload
        await websocket.accept()
        try:
            handshake_raw = await asyncio.wait_for(websocket.receive_text(), timeout=5.0)
            handshake = json.loads(handshake_raw)
            if handshake.get("type") == "auth":
                provided_token = handshake.get("device_token") or handshake.get("token")
                if provided_token and hmac.compare_digest(provided_token, DEVICE_AUTH_TOKEN):
                    authenticated = True
                elif provided_token:
                    try:
                        decode_token(provided_token)
                        authenticated = True
                    except Exception:
                        pass
            if not authenticated:
                await websocket.send_text(json.dumps({"type": "error", "message": "Unauthorized device"}))
                await websocket.close(code=1008, reason="Unauthorized device")
                return
        except Exception:
            try:
                await websocket.close(code=1008, reason="Authentication timeout")
            except Exception:
                pass
            return
    else:
        await websocket.accept()

    db = next(get_db())
    esp32_manager.register_device(device_id, mode="wifi", ws=websocket)
    print(f"ESP32 device authenticated and connected via Wi-Fi: {device_id}")

    try:
        await websocket.send_text(json.dumps({"type": "connected", "device_id": device_id}))

        while True:
            data = await websocket.receive_text()
            msg = json.loads(data)

            if msg.get("type") == "telemetry":
                esp32_manager.update_device(device_id, msg)
                # Save Wi-Fi telemetry to DB and trigger alert rules engine
                soldier_id = msg.get("soldier_id")
                if soldier_id:
                    try:
                        from vitals import process_vitals_reading, VitalsIn
                        vitals_body = VitalsIn(
                            soldier_id=soldier_id,
                            hr=msg.get("hr"),
                            spo2=msg.get("spo2"),
                            temp=msg.get("temp"),
                            battery=msg.get("battery"),
                            device_id=device_id,
                            connection_type="wifi",
                        )
                        await process_vitals_reading(vitals_body, db)
                    except Exception as e:
                        print(f"Wi-Fi vitals save error: {e}")
                await websocket.send_text(json.dumps({"type": "ack", "status": "telemetry_received"}))
                
            elif msg.get("type") == "command_response":
                print(f"ESP32 {device_id} acknowledged command: {msg.get('action')}")
                
            elif msg.get("type") == "ping":
                await websocket.send_text(json.dumps({"type": "pong"}))

    except WebSocketDisconnect:
        esp32_manager.mark_offline(device_id)
        print(f"ESP32 device disconnected: {device_id}")
    except Exception as e:
        print(f"ESP32 WebSocket error: {e}")
        esp32_manager.mark_offline(device_id)
    finally:
        db.close()


# ── Push functions called from other routes ───────────────────────
async def push_vitals_update(soldier_id: str, db: Session):
    message = build_vitals_message(soldier_id, db)
    if message:
        await manager.push(message, feed="vitals")

async def push_location_update(soldier_id: str, db: Session):
    message = build_location_message(soldier_id, db)
    if message:
        await manager.push(message, feed="map")

async def push_alert(alert: AlertModel, db: Session):
    message = build_alert_message(alert, db)
    if message:
        await manager.push(message, feed="alerts")