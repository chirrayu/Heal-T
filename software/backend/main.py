# Starts the FastAPI server, registers all routes, initializes the database and WebSocket.
from contextlib import asynccontextmanager

from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from database import init_db, SessionLocal
from config import HOST, PORT, RENDER_EXTERNAL_URL, ENVIRONMENT, validate_production_settings
from auth import get_current_admin, reset_password_from_environment
from alerts_notifier import init_firebase, register_device_token, unregister_device_token

from auth import router as auth_router
from soldiers import router as soldiers_router
from vitals import router as vitals_router
from alerts import router as alerts_router
from squads import router as squads_router
from suit_config import router as suit_config_router
from map_tracking import router as map_router
from websocket import router as ws_router

import asyncio
import httpx


# ── Self-ping to keep Render free tier alive ─────────────────────
# Hardcoded fallback so the ping works even before Render injects
# RENDER_EXTERNAL_URL into the process environment on first deploy.
_PRODUCTION_URL = "https://triage-ai-backend-trpy.onrender.com"
_PING_TARGET = RENDER_EXTERNAL_URL or _PRODUCTION_URL

async def self_ping():
    """Async ping of our own health endpoint every 14 minutes to
    prevent Render free-tier cold starts. Uses httpx so it never
    blocks the event loop."""
    url = f"{_PING_TARGET}/"
    print(f"Self-ping started → {url} every 14 min")
    async with httpx.AsyncClient(timeout=10) as client:
        while True:
            await asyncio.sleep(14 * 60)
            try:
                await client.get(url)
                print("Self-ping OK")
            except Exception as e:
                print(f"Self-ping failed: {e}")


# ── Lifespan (replaces deprecated @app.on_event) ─────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown logic using the modern lifespan pattern."""
    # ── Startup ───────────────────────────────────────────────────
    validate_production_settings()
    init_db()
    db = SessionLocal()
    try:
        reset_password_from_environment(db)
    finally:
        db.close()
    init_firebase()
    asyncio.create_task(self_ping())
    print("Server ready")

    yield  # application runs here

    # ── Shutdown ──────────────────────────────────────────────────
    print("Server shutting down")


# ── App factory ───────────────────────────────────────────────────
# Disable interactive docs in production to avoid leaking API surface.
_docs_url    = None if ENVIRONMENT == "production" else "/docs"
_redoc_url   = None if ENVIRONMENT == "production" else "/redoc"
_openapi_url = None if ENVIRONMENT == "production" else "/openapi.json"

app = FastAPI(
    title="Triage AI Backend",
    version="1.0.0",
    lifespan=lifespan,
    docs_url=_docs_url,
    redoc_url=_redoc_url,
    openapi_url=_openapi_url,
)


# ── CORS ──────────────────────────────────────────────────────────
ALLOWED_ORIGINS = [
    "https://triage-ai-backend-trpy.onrender.com",  # production backend URL
    "http://localhost:8000",                          # local dev
    "http://10.0.2.2:8000",                          # Android emulator → host
    "*",
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Health check ──────────────────────────────────────────────────
@app.get("/", tags=["Health"])
def health():
    return {"status": "ok", "app": "Triage AI Backend", "environment": ENVIRONMENT}


# ── Device token registration (admin-only) ────────────────────────
class DeviceTokenIn(BaseModel):
    token: str

@app.post("/device/register", tags=["Device"])
def register_device(body: DeviceTokenIn, admin=Depends(get_current_admin)):
    """Android app calls this on startup with its FCM token.
    Server stores the token and uses it to send push notifications."""
    register_device_token(body.token)
    return {"message": "Device registered"}

@app.post("/device/unregister", tags=["Device"])
def unregister_device(body: DeviceTokenIn, admin=Depends(get_current_admin)):
    """Called when operator logs out."""
    unregister_device_token(body.token)
    return {"message": "Device unregistered"}


# ── Routers ───────────────────────────────────────────────────────
app.include_router(auth_router,        prefix="/auth",       tags=["Auth"])
app.include_router(soldiers_router,    prefix="/soldiers",   tags=["Soldiers"])
app.include_router(vitals_router,      prefix="/vitals",     tags=["Vitals"])
app.include_router(alerts_router,      prefix="/alerts",     tags=["Alerts"])
app.include_router(squads_router,      prefix="/squads",     tags=["Squads"])
app.include_router(suit_config_router, prefix="/suit",       tags=["Suit Config"])
app.include_router(map_router,         prefix="/map",        tags=["Map"])
app.include_router(ws_router,          prefix="/ws",         tags=["WebSocket"])


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=HOST, port=PORT, reload=True)
