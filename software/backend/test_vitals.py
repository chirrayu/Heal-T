import os
import requests
from config import ADMIN_USERNAME, ADMIN_PASSWORD, PORT, HOST

BASE = f"http://localhost:{PORT}"

res = requests.post(f"{BASE}/auth/login", data={
    "username": ADMIN_USERNAME,
    "password": ADMIN_PASSWORD
})
if res.status_code != 200:
    print(f"Login failed: {res.status_code} {res.text}")
    exit(1)
token = res.json()["access_token"]

headers = {"Authorization": f"Bearer {token}"}
soldiers = requests.get(f"{BASE}/soldiers/", headers=headers).json()

vitals_data = [
    {"hr": 72,  "spo2": 98, "temp": 98.6,  "battery": 87},
    {"hr": 68,  "spo2": 99, "temp": 98.2,  "battery": 92},
    {"hr": 108, "spo2": 93, "temp": 100.4, "battery": 61},
    {"hr": 138, "spo2": 84, "temp": 103.1, "battery": 34},
    {"hr": None,"spo2": None,"temp": None, "battery": 5},
    {"hr": 65,  "spo2": 98, "temp": 98.4,  "battery": 79},
]

for i, soldier in enumerate(soldiers):
    v = vitals_data[i] if i < len(vitals_data) else vitals_data[0]
    r = requests.post(f"{BASE}/vitals/", json={
        "soldier_id": soldier["id"],
        **v
    }, headers=headers)
    print(f"Vitals posted for {soldier['name']} → {r.status_code}")

print("Done")