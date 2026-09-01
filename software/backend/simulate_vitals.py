import requests
import time
import random
from datetime import datetime, timezone

BASE = "http://localhost:8000"

# Chance per cycle, per soldier, of simulating a blast-magnitude spike —
# lets you see blast_severity / the B multiplier / score respond, without
# waiting for it to happen by random chance.
BLAST_CHANCE = 0.1  # 10%

from config import ADMIN_USERNAME, ADMIN_PASSWORD

# Login
print("Logging in...")
response = requests.post(f"{BASE}/auth/login", data={
    "username": ADMIN_USERNAME,
    "password": ADMIN_PASSWORD
})

if response.status_code != 200:
    # Try fallback default password
    response = requests.post(f"{BASE}/auth/login", data={
        "username": ADMIN_USERNAME,
        "password": "triage2024"
    })

if response.status_code != 200:
    # Try default admin/triage2024
    response = requests.post(f"{BASE}/auth/login", data={
        "username": "admin",
        "password": "triage2024"
    })

if response.status_code != 200:
    print(f"Login failed: {response.text}")
    exit()

token = response.json()["access_token"]
headers = {"Authorization": f"Bearer {token}"}
print("Logged in successfully")

# Get all soldiers
soldiers = requests.get(f"{BASE}/soldiers/").json()
active = [s for s in soldiers if s["status"] != "offline"]

if not active:
    print("No soldiers found. Run seed.py first.")
    exit()

print(f"Simulating vitals for {len(active)} soldiers. Press Ctrl+C to stop.\n")

while True:
    for soldier in active:
        is_blast = random.random() < BLAST_CHANCE

        vitals = {
            "soldier_id": soldier["id"],
            "hr":      random.randint(60, 145),
            "spo2":    random.randint(82, 99),
            "temp":    round(random.uniform(97.5, 104.0), 1),
            "battery": random.randint(5, 100),
            # ⚠ NEW — required by triage.calculate_score(), previously
            # missing entirely so no score was ever computed.
            "activity_index":   random.randint(0, 3),
            "respiratory_rate": random.randint(10, 28),
        }

        if is_blast:
            # Simulate a blast-magnitude accelerometer spike — values
            # chosen to land clearly inside blast.py's detectable range
            # (MIN_DETECTABLE_G=3.0 to SATURATION_G=16.0).
            vitals["peak_accel_g"] = round(random.uniform(4.0, 18.0), 2)
            vitals["duration_ms"]  = round(random.uniform(10, 90), 1)
            vitals["blast_timestamp"] = datetime.now(timezone.utc).isoformat()
        else:
            vitals["peak_accel_g"] = round(random.uniform(0.0, 1.5), 2)
            vitals["duration_ms"]  = 0.0
            vitals["blast_timestamp"] = None

        r = requests.post(f"{BASE}/vitals/", json=vitals)
        if r.status_code == 200:
            result = r.json()
            blast_tag = " [BLAST]" if is_blast else ""
            print(
                f"{soldier['name']} -> HR: {vitals['hr']} | SpO2: {vitals['spo2']}% | "
                f"Temp: {vitals['temp']}F | Battery: {vitals['battery']}% | "
                f"Act: {vitals['activity_index']} | RR: {vitals['respiratory_rate']} | "
                f"Score: {result.get('score')} | Class: {result.get('classification')}"
                f"{blast_tag}"
            )
        else:
            print(f"Error for {soldier['name']}: {r.text}")

    print("--- cycle complete ---\n")
    time.sleep(5)