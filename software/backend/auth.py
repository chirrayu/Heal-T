#Handles admin login, operator sessions, JWT tokens. 
# The admin who edits soldier names, configures suits,
# changes no-movement thresholds needs to be verified.
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from sqlalchemy.orm import Session
from jose import JWTError, jwt
from passlib.context import CryptContext
from datetime import datetime, timedelta
from pydantic import BaseModel
from database import get_db, AdminCredential
from config import (
    SECRET_KEY, ALGORITHM,
    ACCESS_TOKEN_EXPIRE_MINUTES,
    ADMIN_USERNAME, ADMIN_PASSWORD, RESET_ADMIN_PASSWORD
)

router = APIRouter()

# ── Password hashing ──────────────────────────────────────────────
import bcrypt

oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/auth/login")


# ── Schemas ───────────────────────────────────────────────────────
class Token(BaseModel):
    access_token: str
    token_type: str

class TokenData(BaseModel):
    username: str | None = None

class UserOut(BaseModel):
    username: str
    role: str


# ── Helpers ───────────────────────────────────────────────────────
def verify_password(plain: str, hashed: str) -> bool:
    try:
        return bcrypt.checkpw(plain.encode("utf-8"), hashed.encode("utf-8"))
    except Exception:
        return False

def hash_password(plain: str) -> str:
    salt = bcrypt.gensalt()
    return bcrypt.hashpw(plain.encode("utf-8"), salt).decode("utf-8")

def get_or_create_password_hash(db: Session) -> str:
    """
    Returns the persisted admin password hash, bootstrapping it from
    ADMIN_PASSWORD (env/config default) on first run if no row exists yet.
    This is what makes /auth/change-password actually take effect —
    previously ADMIN_PASSWORD was re-hashed from config on every request,
    so a changed password had nowhere to be saved.
    """
    row = db.query(AdminCredential).filter(AdminCredential.id == "admin").first()
    if row is None:
        row = AdminCredential(id="admin", password_hash=hash_password(ADMIN_PASSWORD))
        db.add(row)
        db.commit()
        db.refresh(row)
    return row.password_hash


def reset_password_from_environment(db: Session) -> bool:
    """One-time recovery path for managed deployments without shell access.

    Set RESET_ADMIN_PASSWORD=true in the host environment, deploy once, then
    remove the variable. Leaving it enabled would overwrite any later password
    change each time the service restarts.
    """
    if not RESET_ADMIN_PASSWORD:
        return False

    row = db.query(AdminCredential).filter(AdminCredential.id == "admin").first()
    if row is None:
        row = AdminCredential(id="admin", password_hash=hash_password(ADMIN_PASSWORD))
        db.add(row)
    else:
        row.password_hash = hash_password(ADMIN_PASSWORD)
    db.commit()
    print("WARNING: Admin password reset from environment. Remove RESET_ADMIN_PASSWORD now.")
    return True


def create_access_token(data: dict) -> str:
    to_encode = data.copy()
    expire = datetime.utcnow() + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    to_encode.update({"exp": expire})
    return jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)

def decode_token(token: str) -> TokenData:
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        username: str = payload.get("sub")
        if username is None:
            raise HTTPException(status_code=401, detail="Invalid token")
        return TokenData(username=username)
    except JWTError:
        raise HTTPException(status_code=401, detail="Invalid token")


# ── Dependency: get current admin ────────────────────────────────
# Add this as a dependency to any route that requires login.
# Example:
#   @router.get("/soldiers")
#   def get_soldiers(admin = Depends(get_current_admin)):
#       ...
def get_current_admin(token: str = Depends(oauth2_scheme)) -> UserOut:
    token_data = decode_token(token)
    if token_data.username != ADMIN_USERNAME:
        raise HTTPException(status_code=403, detail="Not authorized")
    return UserOut(username=token_data.username, role="admin")


# ── Routes ────────────────────────────────────────────────────────

# POST /auth/login
# The Android app calls this with username + password.
# Returns a JWT token to use in all future requests.
@router.post("/login", response_model=Token)
def login(
    form_data: OAuth2PasswordRequestForm = Depends(),
    db: Session = Depends(get_db)
):

    # Check username
    if form_data.username != ADMIN_USERNAME:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password"
        )

    # Check password against the persisted hash (bootstrapped from
    # ADMIN_PASSWORD on first run, updated by /auth/change-password after)
    hashed = get_or_create_password_hash(db)
    if not verify_password(form_data.password, hashed):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password"
        )

    token = create_access_token(data={"sub": form_data.username})
    return Token(access_token=token, token_type="bearer")


# GET /auth/me
# Returns info about whoever is currently logged in.
# Useful for the Android app to verify a stored token is still valid.
@router.get("/me", response_model=UserOut)
def get_me(current_admin: UserOut = Depends(get_current_admin)):
    return current_admin


# POST /auth/change-password
# Admin can change the password from the Settings screen.
class ChangePasswordRequest(BaseModel):
    old_password: str
    new_password: str

@router.post("/change-password")
def change_password(
    body: ChangePasswordRequest,
    current_admin: UserOut = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    current_hash = get_or_create_password_hash(db)
    if not verify_password(body.old_password, current_hash):
        raise HTTPException(status_code=400, detail="Old password is incorrect")
    if len(body.new_password) < 6:
        raise HTTPException(status_code=400, detail="New password must be at least 6 characters")

    row = db.query(AdminCredential).filter(AdminCredential.id == "admin").first()
    row.password_hash = hash_password(body.new_password)
    db.commit()

    return {"message": "Password changed successfully"}
