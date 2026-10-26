# Your database models and connection setup (SQLite for simple start, PostgreSQL for production).
# All other files import from here.

from sqlalchemy import (
    create_engine, Column, String, Integer,
    Float, Boolean, DateTime, Text, ForeignKey, event
)
from sqlalchemy.orm import declarative_base, sessionmaker, relationship
from datetime import datetime
from config import DATABASE_URL, SQLCIPHER_KEY, ENVIRONMENT

# ── Engine + session ──────────────────────────────────────────────
_connect_args = {}
if DATABASE_URL.startswith("sqlite"):
    _connect_args["check_same_thread"] = False
elif DATABASE_URL.startswith("postgres://") or DATABASE_URL.startswith("postgresql://"):
    # Supabase and all hosted PostgreSQL (RDS, etc.) require SSL in production.
    # We apply sslmode=require unconditionally for any postgres:// URL so that
    # local docker-compose postgres works too (it accepts but ignores the flag).
    if ENVIRONMENT == "production":
        _connect_args["sslmode"] = "require"

engine = create_engine(
    DATABASE_URL,
    connect_args=_connect_args
)

# Attach SQLCipher encryption key hook if provided
if SQLCIPHER_KEY and DATABASE_URL.startswith("sqlite"):
    @event.listens_for(engine, "connect")
    def set_sqlite_encryption_pragma(dbapi_connection, connection_record):
        cursor = dbapi_connection.cursor()
        safe_key = SQLCIPHER_KEY.replace("'", "''")
        cursor.execute(f"PRAGMA key = '{safe_key}';")
        cursor.execute("PRAGMA cipher_compatibility = 4;")
        cursor.execute("PRAGMA journal_mode = WAL;")
        cursor.close()

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


# ── Models ───────────────────────────────────────────────────────

class Squad(Base):
    __tablename__ = "squads"

    id         = Column(String, primary_key=True)
    name       = Column(String, unique=True, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)

    soldiers = relationship("SoldierModel", back_populates="squad_rel")


class SoldierModel(Base):
    __tablename__ = "soldiers"

    id          = Column(String, primary_key=True)
    name        = Column(String, nullable=False)
    rank_title  = Column(String, nullable=False)
    rank_order  = Column(Integer, nullable=False)
    serial      = Column(String, unique=True, nullable=False)
    squad_id    = Column(String, ForeignKey("squads.id"))
    role        = Column(String)
    blood_group = Column(String, default="O+")
    photo_path  = Column(String, nullable=True)
    status      = Column(String, default="stable")
    created_at  = Column(DateTime, default=datetime.utcnow)

    squad_rel   = relationship("Squad", back_populates="soldiers")
    vitals      = relationship("VitalsModel", back_populates="soldier")
    alerts      = relationship("AlertModel", back_populates="soldier")
    location    = relationship("LocationModel", back_populates="soldier")
    suit_config = relationship("SuitConfigModel", back_populates="soldier", uselist=False)
    
    # ⚠ NEW — Link to the physical ESP32 device assigned to this soldier
    esp32_devices = relationship("ESP32DeviceModel", back_populates="soldier")


class VitalsModel(Base):
    __tablename__ = "vitals"

    id          = Column(Integer, primary_key=True, autoincrement=True)
    soldier_id  = Column(String, ForeignKey("soldiers.id"), nullable=False)
    hr          = Column(Integer, nullable=True)
    spo2        = Column(Integer, nullable=True)
    temp        = Column(Float, nullable=True)
    battery     = Column(Integer, nullable=True)
    recorded_at = Column(DateTime, default=datetime.utcnow)

    # Existing extended fields
    activity_index   = Column(Integer, nullable=True)
    respiratory_rate = Column(Integer, nullable=True)
    blast_severity   = Column(Float, nullable=True)
    blast_timestamp  = Column(DateTime, nullable=True)
    score            = Column(Float, nullable=True)
    classification   = Column(String, nullable=True)

    #  NEW — ESP32 tracking fields (populated by vitals.py process_vitals_reading)
    device_id        = Column(String, nullable=True)   # e.g., ESP32 MAC address or UUID
    connection_type  = Column(String, nullable=True)   # "wifi", "ble", or "suit"

    soldier = relationship("SoldierModel", back_populates="vitals")


class AlertModel(Base):
    __tablename__ = "alerts"

    id              = Column(String, primary_key=True)
    soldier_id      = Column(String, ForeignKey("soldiers.id"), nullable=False)
    title           = Column(String, nullable=False)
    severity        = Column(String, nullable=False)
    message         = Column(Text)
    action_required = Column(Boolean, default=False)
    created_at      = Column(DateTime, default=datetime.utcnow)

    soldier = relationship("SoldierModel", back_populates="alerts")


class LocationModel(Base):
    __tablename__ = "locations"

    id          = Column(Integer, primary_key=True, autoincrement=True)
    soldier_id  = Column(String, ForeignKey("soldiers.id"), nullable=False)
    latitude    = Column(Float, nullable=False)
    longitude   = Column(Float, nullable=False)
    recorded_at = Column(DateTime, default=datetime.utcnow)

    soldier = relationship("SoldierModel", back_populates="location")


class SuitConfigModel(Base):
    __tablename__ = "suit_configs"

    soldier_id          = Column(String, ForeignKey("soldiers.id"), primary_key=True)
    hr_sensor           = Column(Boolean, default=True)
    spo2_sensor         = Column(Boolean, default=True)
    temp_sensor         = Column(Boolean, default=True)
    accelerometer       = Column(Boolean, default=True)
    gps_enabled         = Column(Boolean, default=True)
    sampling_rate_secs  = Column(Integer, default=5)
    wifi_enabled        = Column(Boolean, default=True)
    mesh_enabled        = Column(Boolean, default=True)
    radio_gateway       = Column(Boolean, default=False)
    emergency_mode      = Column(Boolean, default=False)
    updated_at          = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    soldier = relationship("SoldierModel", back_populates="suit_config")


class AdminCredential(Base):
    __tablename__ = "admin_credentials"

    id            = Column(String, primary_key=True, default="admin")
    password_hash = Column(String, nullable=False)


class UserCredential(Base):
    __tablename__ = "user_credentials"

    username      = Column(String, primary_key=True)
    password_hash = Column(String, nullable=False)
    role          = Column(String, nullable=False, default="operator")  # "admin", "commander", "medic", "operator"
    created_at    = Column(DateTime, default=datetime.utcnow)


# ⚠ NEW — Tracks the physical ESP32 hardware state
class ESP32DeviceModel(Base):
    __tablename__ = "esp32_devices"

    id              = Column(Integer, primary_key=True, autoincrement=True)
    device_id       = Column(String, unique=True, nullable=False)  # ESP32 generated UUID or MAC
    soldier_id      = Column(String, ForeignKey("soldiers.id"), nullable=True)
    
    mac_address     = Column(String, unique=True, nullable=True)
    name            = Column(String, nullable=True)                # e.g., "ESP32-Alpha"
    
    connection_mode = Column(String, default="wifi")               # "wifi" or "ble"
    status          = Column(String, default="offline")            # "online", "offline", "pairing"
    
    battery_level   = Column(Integer, nullable=True)
    signal_strength = Column(Integer, nullable=True)               # RSSI
    
    last_seen       = Column(DateTime, default=datetime.utcnow)
    created_at      = Column(DateTime, default=datetime.utcnow)

    soldier = relationship("SoldierModel", back_populates="esp32_devices")


# ── DB init helper ────────────────────────────────────────────────
def init_db():
    Base.metadata.create_all(bind=engine)
    
    # Auto-migrate missing columns on existing SQLite/Postgres tables
    with engine.connect() as conn:
        for table, col, col_type in [
            ("vitals", "device_id", "VARCHAR"),
            ("vitals", "connection_type", "VARCHAR"),
            ("vitals", "blast_severity", "FLOAT"),
            ("vitals", "blast_timestamp", "DATETIME"),
            ("vitals", "score", "FLOAT"),
            ("vitals", "classification", "VARCHAR"),
        ]:
            try:
                conn.execute(__import__("sqlalchemy").text(f"ALTER TABLE {table} ADD COLUMN {col} {col_type}"))
                conn.commit()
            except Exception:
                pass # Column already exists

    # Auto-enable Row Level Security (RLS) on PostgreSQL / Supabase tables
    if engine.dialect.name == "postgresql":
        with engine.connect() as conn:
            for table in [
                "squads", "soldiers", "vitals", "alerts",
                "locations", "suit_configs", "esp32_devices",
                "user_credentials", "admin_credentials"
            ]:
                try:
                    conn.execute(__import__("sqlalchemy").text(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY;"))
                    conn.commit()
                except Exception:
                    pass


# ── Dependency for FastAPI routes ─────────────────────────────────
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_or_create_soldier(soldier_id: str, db) -> SoldierModel:
    """
    Finds a soldier by id, serial, normalized serial, or linked ESP32 device.
    If not found, auto-provisions a new soldier so real hardware connections
    work out of the box without manual pre-registration.
    """
    if not soldier_id:
        raise ValueError("soldier_id cannot be empty")

    # 1. Exact ID match
    soldier = db.query(SoldierModel).filter(SoldierModel.id == soldier_id).first()
    if soldier:
        return soldier

    # 2. Exact Serial match
    soldier = db.query(SoldierModel).filter(SoldierModel.serial == soldier_id).first()
    if soldier:
        return soldier

    # 3. Normalized serial match (e.g., SOLDIER-001 -> S-001 or vice versa)
    norm = soldier_id.replace("SOLDIER-", "S-").replace("SOLDIER_", "S-")
    soldier = db.query(SoldierModel).filter(SoldierModel.serial == norm).first()
    if soldier:
        return soldier

    # 4. Check if linked via ESP32DeviceModel
    device = db.query(ESP32DeviceModel).filter(ESP32DeviceModel.device_id == soldier_id).first()
    if device and device.soldier:
        return device.soldier

    # 5. Check by Name
    soldier = db.query(SoldierModel).filter(SoldierModel.name == soldier_id).first()
    if soldier:
        return soldier

    # 6. Auto-provision so hardware immediately surfaces in the app
    import uuid
    default_squad = db.query(Squad).first()
    if not default_squad:
        default_squad = Squad(id=str(uuid.uuid4()), name="Alpha")
        db.add(default_squad)
        db.commit()
        db.refresh(default_squad)

    soldier = SoldierModel(
        id=soldier_id,
        name=f"Unit {soldier_id}",
        rank_title="Pvt.",
        rank_order=4,
        serial=soldier_id,
        squad_id=default_squad.id,
        role="Field Wearable",
        blood_group="O+",
        status="stable"
    )
    db.add(soldier)
    db.commit()
    db.refresh(soldier)

    # Ensure suit config exists
    config = db.query(SuitConfigModel).filter(SuitConfigModel.soldier_id == soldier.id).first()
    if not config:
        config = SuitConfigModel(soldier_id=soldier.id)
        db.add(config)
        db.commit()

    # Track ESP32 device
    device = db.query(ESP32DeviceModel).filter(ESP32DeviceModel.device_id == soldier_id).first()
    if not device:
        device = ESP32DeviceModel(
            device_id=soldier_id,
            soldier_id=soldier.id,
            name=f"Suit-{soldier_id}",
            connection_mode="wifi",
            status="online"
        )
        db.add(device)
        db.commit()

    return soldier