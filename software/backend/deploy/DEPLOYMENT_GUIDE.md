# Production Deployment Guide: AWS EC2 & AWS RDS PostgreSQL

This directory contains all pre-configured files and scripts to deploy the **Triage AI Backend** on an **AWS EC2 Ubuntu Server** connected to an **AWS RDS PostgreSQL Database**.

---

## 📂 Deployment Files Overview

| File | Purpose |
| :--- | :--- |
| **`setup_ec2.sh`** | Automated one-click script to install dependencies, configure Nginx, setup systemd, and train ML models |
| **`nginx.conf`** | Pre-configured Nginx reverse proxy with persistent WebSocket (`/ws/`) upgrade support |
| **`triage-backend.service`** | Systemd unit file to run FastAPI via Gunicorn + Uvicorn workers 24/7 with auto-restart |
| **`env.production.example`** | Template for production `.env` with RDS PostgreSQL connection string |
| **`Dockerfile` & `docker-compose.yml`** | Containerized deployment option |

---

## 🚀 Quick Deployment (Standard Systemd + Nginx)

### 1. Launch AWS RDS (PostgreSQL)
1. In AWS RDS Console, create a **PostgreSQL** instance (Free tier / `db.t3.micro`).
2. Set Master Username (`postgres`) and Master Password.
3. In the RDS Security Group (`rds-triage-sg`), add an **Inbound Rule**:
   * Type: `PostgreSQL` | Port: `5432` | Source: Select your **EC2 Security Group** (`ec2-triage-sg`).
4. Copy the RDS Endpoint (e.g. `triage-ai-db.xxxxxx.ap-south-1.rds.amazonaws.com`).

### 2. Launch AWS EC2 Instance
1. Launch an **Ubuntu 24.04 LTS** instance (`t3.micro` or `t3.small`).
2. In the Security Group (`ec2-triage-sg`), allow:
   * **SSH (22)** from your IP
   * **HTTP (80)** from `0.0.0.0/0`
   * **HTTPS (443)** from `0.0.0.0/0`

### 3. SSH into EC2 and Run Setup
Connect to your EC2 instance from your terminal:
```bash
ssh -i /path/to/key.pem ubuntu@<YOUR_EC2_PUBLIC_IP>
```

Clone the repository and run the automated setup script:
```bash
git clone https://github.com/<YOUR_USERNAME>/<YOUR_REPO>.git triage-backend
cd triage-backend/software/backend

# Make script executable and run
chmod +x deploy/setup_ec2.sh
./deploy/setup_ec2.sh
```

### 4. Configure Your RDS Database Connection
Open `.env` and paste your RDS PostgreSQL credentials:
```bash
nano .env
```
Update the `DATABASE_URL` line:
```ini
DATABASE_URL=postgresql://postgres:YourSecurePassword@your-rds-endpoint.rds.amazonaws.com:5432/postgres
SECRET_KEY=generate-a-random-32-character-secret-key-here
ADMIN_USERNAME=admin
ADMIN_PASSWORD=YourSecureAdminPassword
```
*(Press `Ctrl+O` and `Enter` to save, then `Ctrl+X` to exit)*.

### 5. Seed Database & Restart Backend
```bash
source venv/bin/activate
python seed.py
sudo systemctl restart triage-backend
```

### 6. Verify Deployment
* Check backend status: `sudo systemctl status triage-backend`
* Test API in browser / curl: `curl http://<YOUR_EC2_PUBLIC_IP>/`
  * Should return: `{"status":"ok","app":"Triage AI Backend","environment":"production"}`

---

## 📱 Update Android Frontend

Open [NetworkConfig.kt](file:///c:/Users/monik/Desktop/research/Wearable-Health-Track-Device/software/frontend/app/src/main/java/com/example/healthmonitor/NetworkConfig.kt) in Android Studio and update the IP:

```kotlin
package com.example.healthmonitor

object NetworkConfig {
    const val BASE_URL = "http://<YOUR_EC2_PUBLIC_IP>"
    const val WS_URL   = "ws://<YOUR_EC2_PUBLIC_IP>/ws/connect?feed=all"
}
```

---

## 🔒 Optional: Add Free SSL (HTTPS & WSS) via Certbot
Once a domain name points to your EC2 IP:
```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d yourdomain.com
```
Certbot will automatically configure HTTPS on port 443 and update your Nginx config.
