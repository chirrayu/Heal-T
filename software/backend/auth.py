# Handles user and admin login, operator sessions, JWT tokens, RBAC,
# device ingestion authentication, and brute-force rate limiting.
import hmac
import hashlib
import time
from datetime import datetime, timedelta
from typing import List, Optional, Callable

from fastapi import APIRouter, Depends, HTTPException, status, Request, Header
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from sqlalchemy.orm import Session
from jose import JWTError, jwt
import bcrypt
from pydantic import BaseModel, Field, ConfigDict

from database import get_db, AdminCredential, UserCredential, ESP32DeviceModel
from config import (
    SECRET_KEY, ALGORITHM,
    ACCESS_TOKEN_EXPIRE_MINUTES,
    ADMIN_USERNAME, ADMIN_PASSWORD, RESET_ADMIN_PASSWORD,
    DEVICE_AUTH_TOKEN, DEVICE_HMAC_SECRET,
    LOGIN_RATE_LIMIT_PER_MINUTE, PASSWORD_RATE_LIMIT_PER_MINUTE
)
from rate_limiter import rate_limit

router = APIRouter()

oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/auth/login", auto_error=False)


# ── Schemas ───────────────────────────────────────────────────────
class Token(BaseModel):
    access_token: str
    token_type: str
    role: str = "operator"
    username: str

class TokenData(BaseModel):
    username: Optional[str] = None
    role: Optional[str] = "operator"

class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    username: str
    role: str
    created_at: Optional[datetime] = None

class UserCreate(BaseModel):
    username: str
    password: str = Field(..., min_length=6)
    role: str = Field("operator", pattern="^(admin|commander|medic|operator)$")

class ChangePasswordRequest(BaseModel):
    old_password: str
    new_password: str = Field(..., min_length=6)


# ── Password Hashing Helpers ──────────────────────────────────────
def verify_password(plain: str, hashed: str) -> bool:
    try:
        return bcrypt.checkpw(plain.encode("utf-8"), hashed.encode("utf-8"))
    except Exception:
        return False

def hash_password(plain: str) -> str:
    salt = bcrypt.gensalt()
    return bcrypt.hashpw(plain.encode("utf-8"), salt).decode("utf-8")


def bootstrap_default_admin(db: Session) -> UserCredential:
    """Ensure default admin account exists in UserCredential table."""
    admin_user = db.query(UserCredential).filter(UserCredential.username == ADMIN_USERNAME).first()
    if admin_user is None:
        # Check if legacy AdminCredential exists
        legacy_admin = db.query(AdminCredential).filter(AdminCredential.id == "admin").first()
        password_hash = legacy_admin.password_hash if legacy_admin else hash_password(ADMIN_PASSWORD)
        
        admin_user = UserCredential(
            username=ADMIN_USERNAME,
            password_hash=password_hash,
            role="admin"
        )
        db.add(admin_user)
        if legacy_admin is None:
            db.add(AdminCredential(id="admin", password_hash=password_hash))
        db.commit()
        db.refresh(admin_user)
    return admin_user


def reset_password_from_environment(db: Session) -> bool:
    """One-time recovery path for managed deployments without shell access."""
    if not RESET_ADMIN_PASSWORD:
        return False

    admin_user = db.query(UserCredential).filter(UserCredential.username == ADMIN_USERNAME).first()
    new_hash = hash_password(ADMIN_PASSWORD)
    if admin_user is None:
        admin_user = UserCredential(username=ADMIN_USERNAME, password_hash=new_hash, role="admin")
        db.add(admin_user)
    else:
        admin_user.password_hash = new_hash

    # Sync legacy table as well
    legacy = db.query(AdminCredential).filter(AdminCredential.id == "admin").first()
    if legacy is None:
        db.add(AdminCredential(id="admin", password_hash=new_hash))
    else:
        legacy.password_hash = new_hash

    db.commit()
    print("WARNING: Admin password reset from environment. Remove RESET_ADMIN_PASSWORD now.")
    return True


# ── JWT Token Management ──────────────────────────────────────────
def create_access_token(data: dict) -> str:
    to_encode = data.copy()
    expire = datetime.utcnow() + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    to_encode.update({"exp": expire})
    return jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)

def decode_token(token: str) -> TokenData:
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        username: str = payload.get("sub")
        role: str = payload.get("role", "operator")
        if username is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid token claims",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return TokenData(username=username, role=role)
    except JWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not validate credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )


# ── Authentication & RBAC Dependencies ────────────────────────────
def get_current_user(
    token: Optional[str] = Depends(oauth2_scheme),
    db: Session = Depends(get_db)
) -> UserOut:
    """Extract and validate the currently authenticated user from Bearer token."""
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Not authenticated",
            headers={"WWW-Authenticate": "Bearer"},
        )
    token_data = decode_token(token)
    user = db.query(UserCredential).filter(UserCredential.username == token_data.username).first()
    if user is None:
        # Check if legacy admin matches
        if token_data.username == ADMIN_USERNAME:
            bootstrap_default_admin(db)
            return UserOut(username=ADMIN_USERNAME, role="admin")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User not found",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return UserOut(username=user.username, role=user.role, created_at=user.created_at)


def require_roles(*allowed_roles: str) -> Callable:
    """Enforce that the authenticated user possesses one of the allowed roles (Admin is always permitted)."""
    def role_checker(current_user: UserOut = Depends(get_current_user)) -> UserOut:
        if current_user.role == "admin" or current_user.role in allowed_roles:
            return current_user
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Access denied. Requires role: {', '.join(allowed_roles)}"
        )
    return role_checker


# Convenient role shortcut dependencies
get_current_admin     = require_roles("admin")
get_current_commander = require_roles("commander")
get_current_medic     = require_roles("medic")
get_current_operator  = require_roles("admin", "commander", "medic", "operator")


# ── Ingestion Device Authentication (HMAC / Device Token) ─────────
async def verify_ingestion_auth(
    request: Request,
    authorization: Optional[str] = Header(None),
    x_device_token: Optional[str] = Header(None),
    x_signature: Optional[str] = Header(None),
    x_timestamp: Optional[str] = Header(None),
    db: Session = Depends(get_db)
) -> dict:
    """
    Authenticates telemetry ingestion from wearable suits or operator devices.
    Accepts:
      1. Valid user Bearer JWT token
      2. Valid Device Token (X-Device-Token header)
      3. Valid HMAC-SHA256 signature (X-Signature + X-Timestamp headers)
    """
    # 1. Check Bearer JWT Token
    if authorization and authorization.startswith("Bearer "):
        token = authorization.split(" ")[1].strip()
        try:
            token_data = decode_token(token)
            return {"auth_type": "jwt", "identifier": token_data.username, "role": token_data.role}
        except Exception:
            pass  # Fall through to device token checks

    # 2. Check Device Token (Constant-time comparison against configured secret)
    if x_device_token:
        if hmac.compare_digest(x_device_token, DEVICE_AUTH_TOKEN):
            return {"auth_type": "device_token", "identifier": "suit_gateway", "role": "device"}

    # 3. Check HMAC-SHA256 Signature
    if x_signature and x_timestamp:
        try:
            req_ts = float(x_timestamp)
            # Replay attack prevention: timestamp must be within 300 seconds
            if abs(time.time() - req_ts) <= 300:
                body_bytes = await request.body()
                message = f"{x_timestamp}".encode("utf-8") + body_bytes
                expected_sig = hmac.new(
                    DEVICE_HMAC_SECRET.encode("utf-8"),
                    message,
                    hashlib.sha256
                ).hexdigest()
                if hmac.compare_digest(expected_sig, x_signature):
                    return {"auth_type": "hmac_signature", "identifier": "signed_device", "role": "device"}
        except Exception:
            pass

    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Unauthorized. Valid user Bearer token, X-Device-Token, or X-Signature required.",
        headers={"WWW-Authenticate": "Bearer, Device-Token"}
    )


# ── Routes ────────────────────────────────────────────────────────

# POST /auth/login (with brute-force rate limiting)
@router.post("/login", response_model=Token, dependencies=[Depends(rate_limit(LOGIN_RATE_LIMIT_PER_MINUTE, 60))])
def login(
    form_data: OAuth2PasswordRequestForm = Depends(),
    db: Session = Depends(get_db)
):
    bootstrap_default_admin(db)

    # Look up user in UserCredential table
    user = db.query(UserCredential).filter(UserCredential.username == form_data.username).first()
    
    # Check legacy fallback if not found
    if user is None and form_data.username == ADMIN_USERNAME:
        legacy = db.query(AdminCredential).filter(AdminCredential.id == "admin").first()
        if legacy and verify_password(form_data.password, legacy.password_hash):
            user = UserCredential(username=ADMIN_USERNAME, password_hash=legacy.password_hash, role="admin")
            db.add(user)
            db.commit()

    if user is None or not verify_password(form_data.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )

    token = create_access_token(data={"sub": user.username, "role": user.role})
    return Token(access_token=token, token_type="bearer", role=user.role, username=user.username)


# GET /auth/me
@router.get("/me", response_model=UserOut)
def get_me(current_user: UserOut = Depends(get_current_user)):
    return current_user


# POST /auth/change-password (with rate limiting)
@router.post("/change-password", dependencies=[Depends(rate_limit(PASSWORD_RATE_LIMIT_PER_MINUTE, 60))])
def change_password(
    body: ChangePasswordRequest,
    current_user: UserOut = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    user = db.query(UserCredential).filter(UserCredential.username == current_user.username).first()
    if not user or not verify_password(body.old_password, user.password_hash):
        raise HTTPException(status_code=400, detail="Old password is incorrect")

    user.password_hash = hash_password(body.new_password)
    
    # Sync legacy admin if applicable
    if current_user.username == ADMIN_USERNAME:
        legacy = db.query(AdminCredential).filter(AdminCredential.id == "admin").first()
        if legacy:
            legacy.password_hash = user.password_hash

    db.commit()
    return {"message": "Password changed successfully"}


# ── RBAC User Management Endpoints (Admin Only) ───────────────────

# POST /auth/users — create a new operator/medic/commander account
@router.post("/users", response_model=UserOut)
def create_user(
    body: UserCreate,
    admin: UserOut = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    existing = db.query(UserCredential).filter(UserCredential.username == body.username.strip()).first()
    if existing:
        raise HTTPException(status_code=400, detail="Username already exists")

    new_user = UserCredential(
        username=body.username.strip(),
        password_hash=hash_password(body.password),
        role=body.role
    )
    db.add(new_user)
    db.commit()
    db.refresh(new_user)
    return UserOut(username=new_user.username, role=new_user.role, created_at=new_user.created_at)


# GET /auth/users — list all system users
@router.get("/users", response_model=List[UserOut])
def list_users(
    admin: UserOut = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    bootstrap_default_admin(db)
    users = db.query(UserCredential).all()
    return [UserOut(username=u.username, role=u.role, created_at=u.created_at) for u in users]
