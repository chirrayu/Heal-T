# Automated security and RBAC test suite for FastAPI backend
import os
import json
import uuid
import pytest
import hmac
import hashlib
import time
from fastapi.testclient import TestClient

# Ensure test DB or environment is set before importing app
os.environ["ENVIRONMENT"] = "development"
os.environ["SECRET_KEY"] = "test-secret-key-1234567890"
os.environ["ADMIN_USERNAME"] = "admin"
os.environ["ADMIN_PASSWORD"] = "triage2024"
os.environ["DEVICE_AUTH_TOKEN"] = "suit-dev-token-secret"
os.environ["DEVICE_HMAC_SECRET"] = "device-hmac-secret-key"

from main import app
from database import init_db, SessionLocal, Squad, SoldierModel
from auth import hash_password, UserCredential

client = TestClient(app)

# ── Login caches ─────────────────────────────────────────────────
# /auth/login is rate-limited (5/min per client) and the whole suite
# shares one test client IP, so each identity logs in at most once
# per test session.
_admin_headers_cache = None
def admin_headers_cached():
    global _admin_headers_cache
    if _admin_headers_cache is None:
        res = client.post("/auth/login", data={"username": "admin", "password": "triage2024"})
        assert res.status_code == 200
        _admin_headers_cache = {"Authorization": f"Bearer {res.json()['access_token']}"}
    return _admin_headers_cache

_operator_headers_cache = None
def operator_headers_cached():
    global _operator_headers_cache
    if _operator_headers_cache is None:
        # Create the user directly in the DB (via the API would cost an
        # extra admin login against the rate limit)
        db = SessionLocal()
        try:
            if not db.query(UserCredential).filter(UserCredential.username == "operator_user").first():
                db.add(UserCredential(
                    username="operator_user",
                    password_hash=hash_password("password123"),
                    role="operator"
                ))
                db.commit()
        finally:
            db.close()
        res = client.post("/auth/login", data={"username": "operator_user", "password": "password123"})
        assert res.status_code == 200
        _operator_headers_cache = {"Authorization": f"Bearer {res.json()['access_token']}"}
    return _operator_headers_cache


@pytest.fixture(scope="session", autouse=True)
def setup_test_db():
    init_db()
    db = SessionLocal()
    try:
        # Create test squad
        squad = db.query(Squad).filter(Squad.id == "test-squad-1").first()
        if not squad:
            squad = Squad(id="test-squad-1", name="Alpha Security Test")
            db.add(squad)
            db.commit()

        # Create test soldier
        soldier = db.query(SoldierModel).filter(SoldierModel.id == "SOLDIER-SEC-01").first()
        if not soldier:
            soldier = SoldierModel(
                id="SOLDIER-SEC-01",
                name="Security Test Soldier",
                rank_title="Cpt.",
                rank_order=1,
                serial="SEC-001",
                squad_id="test-squad-1",
                role="Tester",
                blood_group="O+",
                status="stable"
            )
            db.add(soldier)
            db.commit()
    finally:
        db.close()


def test_unauthenticated_endpoints_return_401():
    """Verify all sensitive read endpoints strictly reject unauthenticated calls."""
    unauth_endpoints = [
        ("GET", "/soldiers/"),
        ("GET", "/soldiers/SOLDIER-SEC-01"),
        ("GET", "/vitals/all/latest"),
        ("GET", "/vitals/SOLDIER-SEC-01/latest"),
        ("GET", "/vitals/SOLDIER-SEC-01/history"),
        ("GET", "/map/live"),
        ("GET", "/map/SOLDIER-SEC-01/latest"),
        ("GET", "/map/SOLDIER-SEC-01/trail"),
        ("GET", "/alerts/"),
        ("GET", "/alerts/summary/counts"),
        ("GET", "/squads/"),
        ("GET", "/suit/SOLDIER-SEC-01"),
    ]

    for method, path in unauth_endpoints:
        response = client.request(method, path)
        assert response.status_code == 401, f"{method} {path} expected 401 but got {response.status_code}"


def test_admin_login_and_access():
    """Verify Admin can log in and access protected endpoints."""
    login_res = client.post("/auth/login", data={
        "username": "admin",
        "password": "triage2024"
    })
    assert login_res.status_code == 200
    token_data = login_res.json()
    assert "access_token" in token_data
    assert token_data["role"] == "admin"
    token = token_data["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    # Admin access read routes
    res = client.get("/soldiers/", headers=headers)
    assert res.status_code == 200
    assert len(res.json()) >= 1


def test_rbac_roles_and_privilege_separation():
    """Verify role creation, permissions, and forbidden access for restricted roles."""
    # 1. Admin logs in (cached — protects the session from the login rate limit)
    admin_headers = admin_headers_cached()

    # 2. Admin creates a medic and a commander
    client.post("/auth/users", json={"username": "medic_user", "password": "password123", "role": "medic"}, headers=admin_headers)
    client.post("/auth/users", json={"username": "commander_user", "password": "password123", "role": "commander"}, headers=admin_headers)

    # 3. Medic logs in
    medic_login = client.post("/auth/login", data={"username": "medic_user", "password": "password123"}).json()
    medic_headers = {"Authorization": f"Bearer {medic_login['access_token']}"}

    # Medic can view vitals and soldiers
    assert client.get("/vitals/all/latest", headers=medic_headers).status_code == 200
    assert client.get("/soldiers/", headers=medic_headers).status_code == 200

    # Medic cannot create squad or delete soldier (Admin only) -> 403 Forbidden
    squad_res = client.post("/squads/", json={"name": "Rogue Squad"}, headers=medic_headers)
    assert squad_res.status_code == 403

    # 4. Commander logs in
    cmd_login = client.post("/auth/login", data={"username": "commander_user", "password": "password123"}).json()
    cmd_headers = {"Authorization": f"Bearer {cmd_login['access_token']}"}

    # Commander can view live map and create alerts
    assert client.get("/map/live", headers=cmd_headers).status_code == 200
    alert_res = client.post("/alerts/", json={
        "soldier_id": "SOLDIER-SEC-01",
        "title": "Tactical Order",
        "severity": "information",
        "message": "Regroup at checkpoint",
        "action_required": False
    }, headers=cmd_headers)
    assert alert_res.status_code == 200

    # Commander cannot reset suit config (Admin only) -> 403
    reset_res = client.post("/suit/SOLDIER-SEC-01/reset", headers=cmd_headers)
    assert reset_res.status_code == 403


def test_device_ingestion_authentication():
    """Verify telemetry ingestion endpoints validate device tokens and HMAC signatures."""
    vitals_payload = {
        "soldier_id": "SOLDIER-SEC-01",
        "hr": 78,
        "spo2": 99,
        "temp": 98.6,
        "battery": 95
    }

    # 1. Unauthenticated POST -> 401
    res = client.post("/vitals/", json=vitals_payload)
    assert res.status_code == 401

    # 2. Invalid X-Device-Token -> 401
    res = client.post("/vitals/", json=vitals_payload, headers={"X-Device-Token": "fake-bad-token"})
    assert res.status_code == 401

    # 3. Valid X-Device-Token -> 200
    res = client.post("/vitals/", json=vitals_payload, headers={"X-Device-Token": "suit-dev-token-secret"})
    assert res.status_code == 200
    assert res.json()["hr"] == 78

    # 4. Valid Location POST with Device-Token -> 200
    loc_res = client.post("/map/location", json={
        "soldier_id": "SOLDIER-SEC-01",
        "latitude": 34.0522,
        "longitude": -118.2437
    }, headers={"X-Device-Token": "suit-dev-token-secret"})
    assert loc_res.status_code == 200

    # 5. Valid HMAC Signature authentication
    #    Signed message = "<timestamp>.<nonce>" + raw body; nonce is single-use
    now_ts = str(time.time())
    raw_body = json.dumps(vitals_payload).encode("utf-8")
    nonce = uuid.uuid4().hex
    sig_msg = f"{now_ts}.{nonce}".encode("utf-8") + raw_body
    signature = hmac.new("device-hmac-secret-key".encode("utf-8"), sig_msg, hashlib.sha256).hexdigest()

    hmac_res = client.post(
        "/vitals/",
        content=raw_body,
        headers={
            "Content-Type": "application/json",
            "X-Signature": signature,
            "X-Timestamp": now_ts,
            "X-Nonce": nonce
        }
    )
    assert hmac_res.status_code == 200


def test_ingestion_rejects_non_admin_user_jwt_but_allows_admin():
    """Fix (Vuln 4): an operator JWT is NOT a device identity, so telemetry
    ingestion must reject it; an admin JWT remains allowed as an override."""
    op_headers = operator_headers_cached()
    admin_headers = admin_headers_cached()

    payload = {"soldier_id": "SOLDIER-SEC-01", "hr": 80, "spo2": 98}

    # Operator token -> 401 on both ingestion endpoints
    assert client.post("/vitals/", json=payload, headers=op_headers).status_code == 401
    assert client.post(
        "/map/location",
        json={"soldier_id": "SOLDIER-SEC-01", "latitude": 1.0, "longitude": 2.0},
        headers=op_headers
    ).status_code == 401

    # Admin token -> 200
    res = client.post("/vitals/", json=payload, headers=admin_headers)
    assert res.status_code == 200
    assert res.json()["hr"] == 80


def test_hmac_replay_with_reused_nonce_is_rejected():
    """Fix (Vuln 3): a captured signed request can be used exactly once;
    replaying the same nonce is rejected, and a missing nonce is rejected."""
    payload = {"soldier_id": "SOLDIER-SEC-01", "hr": 82, "spo2": 97}
    raw_body = json.dumps(payload).encode("utf-8")
    now_ts = str(time.time())
    nonce = uuid.uuid4().hex
    sig_msg = f"{now_ts}.{nonce}".encode("utf-8") + raw_body
    signature = hmac.new("device-hmac-secret-key".encode("utf-8"), sig_msg, hashlib.sha256).hexdigest()
    headers = {
        "Content-Type": "application/json",
        "X-Signature": signature,
        "X-Timestamp": now_ts,
        "X-Nonce": nonce
    }

    # First use of the nonce -> accepted
    first = client.post("/vitals/", content=raw_body, headers=headers)
    assert first.status_code == 200

    # Identical replay (same nonce) -> rejected
    replay = client.post("/vitals/", content=raw_body, headers=headers)
    assert replay.status_code == 401
    assert "nonce" in replay.json()["detail"].lower()

    # Signature sent without a nonce -> rejected
    no_nonce = {k: v for k, v in headers.items() if k != "X-Nonce"}
    assert client.post("/vitals/", content=raw_body, headers=no_nonce).status_code == 401


def test_esp32_websocket_rejects_non_admin_jwt():
    """Fix (Vuln 4): the ESP32 telemetry socket accepts the device token or an
    admin JWT only — an operator JWT must be refused."""
    op_token = operator_headers_cached()["Authorization"].split(" ")[1]

    with client.websocket_connect(f"/ws/esp32/ESP32-EVIL?token={op_token}") as ws:
        ws.send_json({"type": "auth", "token": op_token})
        msg = ws.receive_json()
        assert msg["type"] == "error"
        assert msg["message"] == "Unauthorized device"

    # Sanity: the device token path still works
    with client.websocket_connect("/ws/esp32/ESP32-SEC-DEV?device_token=suit-dev-token-secret") as ws:
        init_msg = ws.receive_json()
        assert init_msg["type"] == "connected"


def test_ws_telemetry_forwarding_requires_admin():
    """Fix (Vuln 4): a non-admin /ws/connect client cannot inject BLE telemetry
    or send commands to devices; an admin client can."""
    tele_msg = {
        "type": "esp32_ble_telemetry",
        "device_id": "ESP32-TEST-DEV",
        "data": {"soldier_id": "SOLDIER-SEC-01", "hr": 90, "spo2": 98}
    }

    # Operator: both privileged message types get refused
    op_token = operator_headers_cached()["Authorization"].split(" ")[1]
    with client.websocket_connect(f"/ws/connect?token={op_token}") as ws:
        assert ws.receive_json()["type"] == "snapshot"
        ws.send_json(tele_msg)
        assert ws.receive_json()["type"] == "error"
        ws.send_json({"type": "switch_esp32_mode", "device_id": "ESP32-TEST-DEV", "mode": "ble"})
        assert ws.receive_json()["type"] == "error"

    # Admin: telemetry is accepted (ack). The successfully ingested reading is
    # also broadcast as vitals_update to this "all"-feed socket, so skip any
    # broadcasts until the ack arrives.
    admin_token = admin_headers_cached()["Authorization"].split(" ")[1]
    with client.websocket_connect(f"/ws/connect?token={admin_token}") as ws:
        assert ws.receive_json()["type"] == "snapshot"
        ws.send_json(tele_msg)
        msg = None
        for _ in range(5):
            msg = ws.receive_json()
            if msg["type"] == "ack":
                break
        assert msg is not None and msg["type"] == "ack", f"Never received ack, last message: {msg}"
        assert msg["status"] == "ble_data_received"


def test_production_boot_fails_closed_on_default_device_secrets():
    """Fix (Vuln 2): in production the server must refuse to start while the
    device secrets are still at their public development defaults."""
    import config
    original = (
        config.ENVIRONMENT, config.SECRET_KEY,
        config.ADMIN_USERNAME, config.ADMIN_PASSWORD,
        config.DEVICE_AUTH_TOKEN, config.DEVICE_HMAC_SECRET,
    )
    try:
        config.ENVIRONMENT = "production"
        config.SECRET_KEY = "a-strong-random-secret-32-chars-long-000"
        config.ADMIN_USERNAME = "fieldadmin"
        config.ADMIN_PASSWORD = "Str0ng!Passw0rd#2026"
        config.DEVICE_AUTH_TOKEN = config.DEFAULT_DEVICE_AUTH_TOKEN
        config.DEVICE_HMAC_SECRET = config.DEFAULT_DEVICE_HMAC_SECRET

        with pytest.raises(RuntimeError) as excinfo:
            config.validate_production_settings()
        assert "DEVICE_AUTH_TOKEN" in str(excinfo.value)
        assert "DEVICE_HMAC_SECRET" in str(excinfo.value)

        # With real secrets in place, validation passes
        config.DEVICE_AUTH_TOKEN = "prod-device-token-xyz123"
        config.DEVICE_HMAC_SECRET = "prod-hmac-secret-xyz123"
        config.validate_production_settings()  # must not raise
    finally:
        (config.ENVIRONMENT, config.SECRET_KEY,
         config.ADMIN_USERNAME, config.ADMIN_PASSWORD,
         config.DEVICE_AUTH_TOKEN, config.DEVICE_HMAC_SECRET) = original


def test_login_rate_limiting():
    """Verify repeated login attempts trigger rate limiting (429 Too Many Requests)."""
    # Attempt rapid bad logins from a simulated client IP
    ip_headers = {"X-Forwarded-For": "198.51.100.42"}

    responses = []
    for _ in range(8):
        r = client.post("/auth/login", data={"username": "baduser", "password": "badpassword"}, headers=ip_headers)
        responses.append(r.status_code)

    # First few should be 401 Unauthorized, and subsequent ones must be 429 Too Many Requests
    assert 401 in responses
    assert 429 in responses
    # Verify Retry-After header is present on 429
    last_response = client.post("/auth/login", data={"username": "baduser", "password": "badpassword"}, headers=ip_headers)
    assert last_response.status_code == 429
    assert "retry-after" in last_response.headers


def test_websocket_authentication():
    """Verify WebSocket connections reject unauthenticated clients and accept valid JWT."""
    # 1. Connect with invalid query token -> rejected/closed with exception
    with pytest.raises(Exception):
        with client.websocket_connect("/ws/connect?token=invalid_or_bad_token") as ws:
            pass

    # 2. Connect with valid user token -> accepted and receives snapshot
    #    (cached admin login — the suite must not exceed the login rate limit)
    token = admin_headers_cached()["Authorization"].split(" ")[1]

    with client.websocket_connect(f"/ws/connect?token={token}") as ws:
        snapshot = ws.receive_json()
        assert snapshot["type"] == "snapshot"
        assert "soldiers" in snapshot

    # 3. Connect ESP32 WebSocket with valid device token
    with client.websocket_connect("/ws/esp32/ESP32-SEC-DEV?device_token=suit-dev-token-secret") as ws:
        init_msg = ws.receive_json()
        assert init_msg["type"] == "connected"
        assert init_msg["device_id"] == "ESP32-SEC-DEV"


def test_input_boundaries_and_validation():
    """Verify out-of-range numerical data is rejected with HTTP 422 Unprocessable Entity."""
    headers = {"X-Device-Token": "suit-dev-token-secret"}

    # 1. Test HR upper bound (max 250)
    res = client.post("/vitals/", json={"soldier_id": "SOLDIER-SEC-01", "hr": 350}, headers=headers)
    assert res.status_code == 422

    # 2. Test HR lower bound (min 20)
    res = client.post("/vitals/", json={"soldier_id": "SOLDIER-SEC-01", "hr": 10}, headers=headers)
    assert res.status_code == 422

    # 3. Test SpO2 upper bound (max 100)
    res = client.post("/vitals/", json={"soldier_id": "SOLDIER-SEC-01", "spo2": 110}, headers=headers)
    assert res.status_code == 422

    # 4. Test Temperature bounds (70.0 - 115.0)
    res = client.post("/vitals/", json={"soldier_id": "SOLDIER-SEC-01", "temp": 140.0}, headers=headers)
    assert res.status_code == 422

    res = client.post("/vitals/", json={"soldier_id": "SOLDIER-SEC-01", "temp": 50.0}, headers=headers)
    assert res.status_code == 422

    # 5. Test Latitude bounds (-90 to +90)
    res = client.post("/map/location", json={"soldier_id": "SOLDIER-SEC-01", "latitude": 120.0, "longitude": 45.0}, headers=headers)
    assert res.status_code == 422

    # 6. Test Longitude bounds (-180 to +180)
    res = client.post("/map/location", json={"soldier_id": "SOLDIER-SEC-01", "latitude": 45.0, "longitude": -200.0}, headers=headers)
    assert res.status_code == 422


def test_presigned_url_expiration_and_photo_validation():
    """Verify S3 presigned URL expiration default and image byte validation."""
    from s3helper import get_presigned_url, validate_image_bytes
    from config import S3_PRESIGNED_EXPIRATION_SECONDS

    # 1. Verify default expiration is 15 minutes (900 seconds)
    assert S3_PRESIGNED_EXPIRATION_SECONDS == 900

    # 2. Valid JPEG magic bytes (\xff\xd8\xff)
    valid_jpeg = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01"
    validate_image_bytes(valid_jpeg, "image/jpeg")  # Should not raise

    # 3. Valid PNG magic bytes (\x89PNG\r\n\x1a\n)
    valid_png = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"
    validate_image_bytes(valid_png, "image/png")  # Should not raise

    # 4. Rejection of corrupted / malicious executable bytes masquerading as JPEG
    fake_jpeg = b"MZ\x90\x00\x03\x00\x00\x00"  # Windows EXE header
    with pytest.raises(ValueError, match="Corrupted or invalid JPEG image header"):
        validate_image_bytes(fake_jpeg, "image/jpeg")

    # 5. Rejection of files exceeding 5MB cap
    oversized = b"\xff\xd8\xff" + b"A" * (6 * 1024 * 1024)
    with pytest.raises(ValueError, match="exceeds maximum limit"):
        validate_image_bytes(oversized, "image/jpeg")


def test_ml_model_integrity_and_tamper_protection(tmp_path):
    """Verify SHA-256 integrity verification and tamper protection for ML models."""
    from triage import (
        verify_model_integrity,
        load_ml_models,
        is_ml_active,
        calculate_score,
        compute_sha256,
        _DEFAULT_TRUSTED_CHECKSUMS
    )
    from database import VitalsModel

    # 1. Verify that valid model files are active
    assert load_ml_models(enforce_integrity=True) is True
    assert is_ml_active() is True

    # 2. Verify score calculation works with verified ML model
    dummy_vitals = VitalsModel(
        soldier_id="SOLDIER-SEC-01",
        hr=125,
        spo2=88,
        activity_index=2,
        respiratory_rate=28,
        blast_severity=0.1
    )
    score_res = calculate_score(dummy_vitals, None)
    assert score_res is not None
    assert "score" in score_res
    assert score_res["classification"] in ["Stable", "Serious", "Critical"]

    # 3. Test Tamper Detection: Create fake/tampered model file
    fake_model = tmp_path / "triage_classifier.joblib"
    fake_model.write_bytes(b"MALICIOUS_PICKLE_PAYLOAD_OR_CORRUPT_BYTES")
    
    # Checksum verification must fail on the tampered file
    is_valid, actual_hash, expected_hash = verify_model_integrity(str(fake_model), "triage_classifier.joblib")
    assert is_valid is False
    assert actual_hash != expected_hash

    # 4. Fallback verification: When integrity check fails, ML is deactivated safely
    # and deterministic clinical formula fallback executes without crashing
    dummy_vitals_fallback = VitalsModel(
        soldier_id="SOLDIER-SEC-01",
        hr=75,
        spo2=99,
        activity_index=1,
        respiratory_rate=16,
        blast_severity=0.0
    )
    from triage import _formula_fallback
    fallback_res = _formula_fallback(dummy_vitals_fallback, None)
    assert fallback_res["classification"] == "Stable"
    assert fallback_res["score"] <= 6.5

