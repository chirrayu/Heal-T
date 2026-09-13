"""backend/triage.py

ML-powered TA-CSS scorer implementation with Safe Model Serialization & Integrity Verification.

Security Features:
- SHA-256 integrity checksum verification before calling joblib.load() to prevent arbitrary
  pickle/deserialization code execution attacks.
- Tamper-detection: blocks compromised model files and logs security alerts.
- Automatic deterministic clinical formula fallback if model integrity verification fails or files are missing.
"""

import hashlib
import json
import logging
import os
from typing import Dict, Optional, Tuple

import numpy as np
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)

# ── Base Directory and Paths ──────────────────────────────────────
_BASE_DIR = os.path.dirname(os.path.abspath(__file__))

_classifier_path = os.path.join(_BASE_DIR, "triage_classifier.joblib")
_regressor_path = os.path.join(_BASE_DIR, "triage_regressor.joblib")
_encoder_path = os.path.join(_BASE_DIR, "triage_label_encoder.joblib")
_checksums_path = os.path.join(_BASE_DIR, "triage_checksums.json")

# ── Trusted Default Checksums ─────────────────────────────────────
_DEFAULT_TRUSTED_CHECKSUMS = {
    "triage_classifier.joblib": "f4dfa1d2fc3c8f6ab61dabff833f3a36a17fa6537fc590a353a769513d469117",
    "triage_regressor.joblib": "966291b07c69a29e9d4e7d78d5976a281c73821a09974fbfccbb17a9e5542f98",
    "triage_label_encoder.joblib": "420b4a72f01b2b73ef2f7278ee70535224672e5e96e43a803e35dc8a8d74dad7"
}


class ModelIntegrityError(Exception):
    """Raised when an ML model artifact fails SHA-256 cryptographic integrity verification."""
    pass


def compute_sha256(filepath: str) -> str:
    """Compute cryptographic SHA-256 hash of a file."""
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def get_trusted_checksums() -> Dict[str, str]:
    """Retrieve trusted checksums from triage_checksums.json or default registry."""
    if os.path.exists(_checksums_path):
        try:
            with open(_checksums_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict) and data:
                    return data
        except Exception as e:
            logger.warning(f"[triage] Could not read triage_checksums.json: {e}")
    return _DEFAULT_TRUSTED_CHECKSUMS.copy()


def verify_model_integrity(filepath: str, filename: Optional[str] = None) -> Tuple[bool, str, str]:
    """Verify SHA-256 integrity of an ML model file before loading.
    
    Returns:
        Tuple[is_valid: bool, actual_hash: str, expected_hash: str]
    """
    if not os.path.exists(filepath):
        return False, "", ""
    
    if filename is None:
        filename = os.path.basename(filepath)
        
    trusted = get_trusted_checksums()
    expected_hash = trusted.get(filename, "")
    actual_hash = compute_sha256(filepath)
    
    if not expected_hash:
        logger.warning(f"[triage] No trusted checksum found for model artifact: {filename}")
        return False, actual_hash, ""
        
    is_valid = (actual_hash.lower() == expected_hash.lower())
    return is_valid, actual_hash, expected_hash


_ml_classifier = None
_ml_regressor = None
_ml_label_encoder = None
_ml_available = False


def load_ml_models(enforce_integrity: bool = True) -> bool:
    """Safely load trained ML models with SHA-256 integrity verification.
    
    Prevents arbitrary code execution by ensuring model files match trusted cryptographic hashes
    before calling joblib.load().
    """
    global _ml_classifier, _ml_regressor, _ml_label_encoder, _ml_available
    
    try:
        import joblib
        
        required_files = [
            ("triage_classifier.joblib", _classifier_path),
            ("triage_regressor.joblib", _regressor_path),
            ("triage_label_encoder.joblib", _encoder_path),
        ]
        
        # 1. Check file existence
        for fname, path in required_files:
            if not os.path.exists(path):
                logger.info(f"[triage] ML model file {fname} not found — using formula fallback")
                _ml_available = False
                return False
                
        # 2. Enforce Cryptographic SHA-256 Verification
        if enforce_integrity:
            for fname, path in required_files:
                is_valid, actual, expected = verify_model_integrity(path, fname)
                if not is_valid:
                    error_msg = (
                        f"[SECURITY ALERT] Model integrity verification FAILED for {fname}! "
                        f"Expected SHA256: {expected}, Actual: {actual}. "
                        "Aborting model loading to prevent arbitrary code execution."
                    )
                    logger.error(error_msg)
                    print(error_msg)
                    _ml_available = False
                    raise ModelIntegrityError(error_msg)
                    
        # 3. Safely deserialize verified artifacts
        _ml_classifier = joblib.load(_classifier_path)
        _ml_regressor = joblib.load(_regressor_path)
        _ml_label_encoder = joblib.load(_encoder_path)
        _ml_available = True
        logger.info("[triage] ML models verified and loaded successfully (Random Forest)")
        print("[triage] ML models verified with SHA-256 and loaded successfully (Random Forest)")
        return True
        
    except ModelIntegrityError:
        _ml_available = False
        return False
    except ImportError:
        logger.warning("[triage] joblib/scikit-learn not installed — using formula fallback")
        print("[triage] joblib/scikit-learn not installed — using formula fallback")
        _ml_available = False
        return False
    except Exception as e:
        logger.error(f"[triage] Error loading ML models: {e} — using formula fallback")
        print(f"[triage] Error loading ML models: {e} — using formula fallback")
        _ml_available = False
        return False


# Attempt safe model loading on module import
load_ml_models(enforce_integrity=True)


# ── Fallback: original weighted-sum formula ───────────────────────
# Kept intact so the server always works, even without trained models.

W1 = 0.8  # Heart Rate
W2 = 1.3  # SpO2
W3 = 0.9  # Activity Index
W4 = 1.2  # Respiratory Rate
GAMMA = 1.0  # Hemorrhage weighting


def _hr_score(hr: int) -> int:
    if 60 <= hr <= 100:
        return 0
    if 100 < hr <= 120 or 50 <= hr < 60:
        return 1
    if 120 < hr < 140 or 40 <= hr < 50:
        return 2
    return 3


def _spo2_score(spo2: int) -> int:
    if 95 <= spo2 <= 100:
        return 0
    if 91 <= spo2 <= 94:
        return 1
    if 86 <= spo2 <= 90:
        return 2
    return 3


def _activity_score(activity: int) -> int:
    return activity


def _rr_score(rr: int) -> int:
    if 12 <= rr <= 20:
        return 0
    if 21 <= rr <= 24 or 9 <= rr <= 11:
        return 1
    if 25 <= rr <= 30 or 6 <= rr <= 8:
        return 2
    return 3


def _formula_fallback(vitals, db: Session) -> Dict[str, float]:
    """Original TA-CSS weighted-sum formula (used when ML model is unavailable or untrusted)."""
    hr_sub = _hr_score(vitals.hr)
    spo2_sub = _spo2_score(vitals.spo2)
    act_sub = _activity_score(vitals.activity_index)
    rr_sub = _rr_score(vitals.respiratory_rate)

    weighted_sum = (
        W1 * hr_sub +
        W2 * spo2_sub +
        W3 * act_sub +
        W4 * rr_sub
    )

    from blood_loss import compute_blood_loss_index
    from blast import compute_blast_multiplier
    from trend import get_trend

    if db is not None and getattr(vitals, "recorded_at", None) is not None:
        try:
            trend = get_trend(db, vitals.soldier_id, vitals.recorded_at)
        except Exception:
            trend = {"delta_hr": 0.0, "delta_spo2": 0.0}
    else:
        trend = {"delta_hr": 0.0, "delta_spo2": 0.0}

    L = compute_blood_loss_index(vitals, trend)
    B = compute_blast_multiplier(vitals)

    score = B * weighted_sum + GAMMA * L

    if score <= 6.5:
        classification = "Stable"
    elif score <= 13.5:
        classification = "Serious"
    else:
        classification = "Critical"

    return {"score": score, "classification": classification}


def _ml_predict(vitals) -> Dict[str, float]:
    """Use the verified trained Random Forest model for prediction."""
    blast_severity = getattr(vitals, "blast_severity", None) or 0.0

    features = np.array([[
        float(vitals.hr),
        float(vitals.spo2),
        float(vitals.activity_index),
        float(vitals.respiratory_rate),
        float(blast_severity),
    ]], dtype=np.float32)

    # Predict classification
    class_encoded = _ml_classifier.predict(features)[0]
    classification = _ml_label_encoder.inverse_transform([class_encoded])[0]

    # Predict severity score
    score = float(_ml_regressor.predict(features)[0])

    return {"score": round(score, 4), "classification": str(classification)}


# ── Public API ────────────────────────────────────────────────────
def is_ml_active() -> bool:
    """Check if verified ML models are loaded and active."""
    return _ml_available


def calculate_score(vitals, db: Session) -> Optional[Dict[str, float]]:
    """Calculate the triage severity score for a single Vitals record.

    Uses the verified ML model if available and trusted, otherwise falls back
    to the deterministic clinical weighted-sum formula.
    """
    if vitals.hr is None or vitals.spo2 is None:
        return None
    if vitals.activity_index is None or vitals.respiratory_rate is None:
        return None

    # ── Use verified ML model if available, otherwise formula fallback ─────
    if _ml_available:
        result = _ml_predict(vitals)
    else:
        result = _formula_fallback(vitals, db)

    # Persist onto the row — caller (vitals.py) commits.
    vitals.score = result["score"]
    vitals.classification = result["classification"]

    return result