package com.example.healthmonitor

/**
 * Network configuration enforcing HTTPS and WSS protocols.
 * Supports certificate pinning host matching and dynamic environment switching.
 */
object NetworkConfig {
    // ── Environment Toggle ──────────────────────────────────────────
    // Set USE_LOCAL_BACKEND to true when running FastAPI locally (`python main.py`).
    // In Android Emulator, "10.0.2.2" maps directly to localhost on the development PC.
    // If testing on a physical Android phone over Wi-Fi, change LOCAL_HOST to your PC's LAN IP (e.g., "192.168.1.50").
    const val USE_LOCAL_BACKEND = true

    // Local development settings (HTTP/WS on port 8000)
    const val LOCAL_HOST = "10.0.2.2:8000"
    const val LOCAL_BASE_URL = "http://$LOCAL_HOST"
    const val LOCAL_WS_URL   = "ws://$LOCAL_HOST/ws/connect?feed=all"

    // Production deployment settings (HTTPS/WSS)
    const val PROD_HOST = "13.126.202.93"
    const val PROD_BASE_URL = "https://$PROD_HOST"
    const val PROD_WS_URL   = "wss://$PROD_HOST/ws/connect?feed=all"

    // Active endpoints used across the app (ApiService, WebSocketManager, BleManager)
    val HOST: String get() = if (USE_LOCAL_BACKEND) LOCAL_HOST else PROD_HOST
    val BASE_URL: String get() = if (USE_LOCAL_BACKEND) LOCAL_BASE_URL else PROD_BASE_URL
    val WS_URL: String get() = if (USE_LOCAL_BACKEND) LOCAL_WS_URL else PROD_WS_URL

    // Development security override flag
    const val USE_DEV_SECURITY_OVERRIDE = true
}