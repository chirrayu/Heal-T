#!/bin/bash
# ==============================================================================
# Automated Provisioning Script for AWS EC2 (Ubuntu 22.04 / 24.04 LTS)
# Run on fresh EC2 instance: bash setup_ec2.sh
# ==============================================================================

set -e

echo "=========================================================="
echo "  Starting AWS EC2 Automated Deployment for Triage AI     "
echo "=========================================================="

# 1. Update system packages
echo "[1/7] Updating Ubuntu packages..."
sudo apt update && sudo apt upgrade -y
sudo apt install -y python3-pip python3-venv git nginx libpq-dev curl

# 2. Setup directory & Python virtualenv
echo "[2/7] Setting up Python virtual environment..."
cd /home/ubuntu/triage-backend/software/backend
python3 -m venv venv
source venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt

# 3. Ensure ML models exist
echo "[3/7] Generating dataset & training Machine Learning models..."
if [ ! -f "triage_classifier.joblib" ]; then
    python3 generate_dataset.py
    python3 train_model.py
    echo "ML models trained and verified."
else
    echo "Existing ML models detected."
fi

# 4. Check for .env file
echo "[4/7] Checking environment configuration..."
if [ ! -f ".env" ]; then
    echo "Creating .env from template. PLEASE EDIT .env with your RDS credentials!"
    cp deploy/env.production.example .env
fi

# 5. Configure Systemd Service
echo "[5/7] Installing Systemd Service..."
sudo cp deploy/triage-backend.service /etc/systemd/system/triage-backend.service
sudo systemctl daemon-reload
sudo systemctl enable triage-backend
sudo systemctl restart triage-backend

# 6. Configure Nginx
echo "[6/7] Configuring Nginx Reverse Proxy..."
sudo cp deploy/nginx.conf /etc/nginx/sites-available/triage-backend
sudo ln -sf /etc/nginx/sites-available/triage-backend /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl restart nginx

# 7. Final status check
echo "[7/7] Verifying services..."
echo "--- Nginx Status ---"
sudo systemctl is-active --quiet nginx && echo "✓ Nginx is RUNNING" || echo "✗ Nginx FAILED"

echo "--- Backend Service Status ---"
sudo systemctl is-active --quiet triage-backend && echo "✓ Triage Backend is RUNNING" || echo "✗ Backend FAILED"

echo "=========================================================="
echo "  Deployment Complete!                                    "
echo "  Next steps:                                             "
echo "  1. Edit .env with your RDS PostgreSQL URL: nano .env    "
echo "  2. Populate DB tables: python3 seed.py                  "
echo "  3. Restart backend: sudo systemctl restart triage-backend"
echo "=========================================================="
