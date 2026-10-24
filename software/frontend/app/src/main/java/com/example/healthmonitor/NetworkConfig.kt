package com.example.healthmonitor

/**
 * Network configuration connecting the Android Frontend to the AWS EC2 Backend.
 *
 *  Public IP:  15.206.194.22 (Used by Android APK across Internet / cellular / Wi-Fi)
 *  Private IP: 172.31.8.234  (Used inside AWS VPC / EC2 internal network)
 *  Backend Port: 8000
 */
object NetworkConfig {

    // AWS EC2 IP Configuration
    const val PUBLIC_IP  = "15.206.194.22"
    const val PRIVATE_IP = "172.31.8.234"
    const val PORT       = 8000

    // Set to true only if testing inside Android Emulator against PC localhost (10.0.2.2:8000)
    const val USE_LOCAL_BACKEND = false

    // Local development settings (Android emulator -> PC localhost)
    const val LOCAL_HOST     = "10.0.2.2:$PORT"
    const val LOCAL_BASE_URL = "http://$LOCAL_HOST"
    const val LOCAL_WS_URL   = "ws://$LOCAL_HOST/ws/connect?feed=all"

    // Production AWS EC2 Backend (Public IP)
    const val PROD_HOST     = "$PUBLIC_IP:$PORT"
    const val PROD_BASE_URL = "http://$PROD_HOST"
    const val PROD_WS_URL   = "ws://$PROD_HOST/ws/connect?feed=all"

    // Internal VPC Backend (Private IP)
    const val PRIVATE_HOST     = "$PRIVATE_IP:$PORT"
    const val PRIVATE_BASE_URL = "http://$PRIVATE_HOST"
    const val PRIVATE_WS_URL   = "ws://$PRIVATE_HOST/ws/connect?feed=all"

    // Active endpoints resolved at runtime
    val HOST: String     get() = if (USE_LOCAL_BACKEND) LOCAL_HOST     else PROD_HOST
    val BASE_URL: String get() = if (USE_LOCAL_BACKEND) LOCAL_BASE_URL else PROD_BASE_URL
    val WS_URL: String   get() = if (USE_LOCAL_BACKEND) LOCAL_WS_URL   else PROD_WS_URL

    // Allows user-installed CA certificates in debug builds for Charles / Burp proxying
    const val USE_DEV_SECURITY_OVERRIDE = true
}