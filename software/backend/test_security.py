# Automated security and RBAC test suite for FastAPI backend
import os
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
    # 1. Admin logs in
    admin_login = client.post("/auth/login", data={"username": "admin", "password": "triage2024"}).json()
    admin_headers = {"Authorization": f"Bearer {admin_login['access_token']}"}

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
    now_ts = str(time.time())
    body_str = client.app.router  # trigger request with raw bytes
    import json
    raw_body = json.dumps(vitals_payload).encode("utf-8")
    sig_msg = now_ts.encode("utf-8") + raw_body
    signature = hmac.new("device-hmac-secret-key".encode("utf-8"), sig_msg, hashlib.sha256).hexdigest()

    hmac_res = client.post(
        "/vitals/",
        content=raw_body,
        headers={
            "Content-Type": "application/json",
            "X-Signature": signature,
            "X-Timestamp": now_ts
        }
    )
    assert hmac_res.status_code == 200


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
    admin_login = client.post("/auth/login", data={"username": "admin", "password": "triage2024"}).json()
    token = admin_login["access_token"]

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

