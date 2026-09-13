#Environment variables, secret keys, database URL, FCM credentials, sampling rate defaults — one place for all settings.

from dotenv import load_dotenv
import os
import json
import tempfile

# Load .env or photo.env (whichever exists)
if os.path.exists(".env"):
    load_dotenv(".env")
elif os.path.exists("photo.env"):
    load_dotenv("photo.env")
else:
    load_dotenv()  # still picks up actual env vars on Render

# ── Database ──────────────────────────────────────────────────────
DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./triage_ai.db")
SQLCIPHER_KEY = os.getenv("SQLCIPHER_KEY")  # Optional encryption key for SQLite database at rest
ENVIRONMENT = os.getenv("ENVIRONMENT", "development").lower()

# ── JWT Auth ──────────────────────────────────────────────────────
SECRET_KEY = os.getenv("SECRET_KEY", "change-this-in-production")
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 60 * 8   # 8 hour sessions

# ── Admin credentials (change before deployment) ──────────────────
ADMIN_USERNAME = os.getenv("ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.getenv("ADMIN_PASSWORD", "triage2024")
# Set only for one deployment when the stored admin password needs recovery.
# Remove it immediately after a successful login.
RESET_ADMIN_PASSWORD = os.getenv("RESET_ADMIN_PASSWORD", "false").lower() == "true"

# ── Device & Telemetry Ingestion Authentication ──────────────────
DEVICE_AUTH_TOKEN = os.getenv("DEVICE_AUTH_TOKEN", "suit-dev-token-secret")
DEVICE_HMAC_SECRET = os.getenv("DEVICE_HMAC_SECRET", "device-hmac-secret-key")

# ── Rate Limiting ────────────────────────────────────────────────
LOGIN_RATE_LIMIT_PER_MINUTE = int(os.getenv("LOGIN_RATE_LIMIT_PER_MINUTE", "5"))
PASSWORD_RATE_LIMIT_PER_MINUTE = int(os.getenv("PASSWORD_RATE_LIMIT_PER_MINUTE", "5"))


def validate_production_settings() -> None:
    """Fail closed when a hosted service was started without real secrets."""
    if ENVIRONMENT != "production":
        return
    invalid = []
    if SECRET_KEY == "change-this-in-production":
        invalid.append("SECRET_KEY")
    if ADMIN_USERNAME == "admin":
        invalid.append("ADMIN_USERNAME")
    if ADMIN_PASSWORD == "triage2024":
        invalid.append("ADMIN_PASSWORD")
    if invalid:
        raise RuntimeError(
            "Production settings are unsafe. Set non-default values for: "
            + ", ".join(invalid)
        )

# ── Firebase (for push notifications) ────────────────────────────
# On Render you can't upload files, so pass the entire service-account
# JSON as the FIREBASE_CREDENTIALS_JSON env var.
_firebase_json_str = os.getenv("FIREBASE_CREDENTIALS_JSON")
if _firebase_json_str:
    # Write the JSON string to a temp file so firebase-admin can load it
    _tmp = tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False
    )
    _tmp.write(_firebase_json_str)
    _tmp.close()
    FIREBASE_CREDENTIALS_PATH = _tmp.name
else:
    FIREBASE_CREDENTIALS_PATH = os.getenv(
        "FIREBASE_CREDENTIALS_PATH",
        "firebase_credentials.json"
    )

# ── Alert thresholds (match your Android rules engine) ───────────
HR_CRITICAL_THRESHOLD    = int(os.getenv("HR_CRITICAL_THRESHOLD", "130"))
SPO2_CRITICAL_THRESHOLD  = int(os.getenv("SPO2_CRITICAL_THRESHOLD", "90"))
TEMP_CRITICAL_THRESHOLD  = float(os.getenv("TEMP_CRITICAL_THRESHOLD", "103.0"))
NO_MOVEMENT_MINUTES      = int(os.getenv("NO_MOVEMENT_MINUTES", "30"))

# ── Server ────────────────────────────────────────────────────────
HOST = os.getenv("HOST", "0.0.0.0")
PORT = int(os.getenv("PORT", "8000"))

# ── Render (for self-ping uptime bot) ────────────────────────────
RENDER_EXTERNAL_URL = os.getenv("RENDER_EXTERNAL_URL")  # auto-set by Render
# ── AWS S3 ────────────────────────────────────────────────────────
AWS_ACCESS_KEY_ID     = os.getenv("AWS_ACCESS_KEY_ID")
AWS_SECRET_ACCESS_KEY = os.getenv("AWS_SECRET_ACCESS_KEY")
AWS_REGION            = os.getenv("AWS_REGION", "ap-south-1")
S3_BUCKET_NAME        = os.getenv("S3_BUCKET_NAME", "triage-ai-photos")
S3_PRESIGNED_EXPIRATION_SECONDS = int(os.getenv("S3_PRESIGNED_EXPIRATION_SECONDS", "900"))  # 15 min default
MAX_UPLOAD_PHOTO_BYTES = int(os.getenv("MAX_UPLOAD_PHOTO_BYTES", str(5 * 1024 * 1024)))    # 5 MB
