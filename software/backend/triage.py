"""backend/triage.py

ML-powered TA-CSS scorer implementation.

This module loads a pre-trained Random Forest model (trained by train_model.py)
and uses it to predict the triage classification and severity score for each
incoming vitals reading.  If the model files are not found (e.g. first deploy
before training), the original weighted-sum formula is used as a fallback.

vitals.py calls calculate_score() with a freshly-saved VitalsModel row and
the active DB session.  Returns a dict with the numeric severity score and
the textual classification band, and also writes both onto the vitals
row (caller is responsible for committing).
"""

import os
from typing import Dict, Optional

import numpy as np
from sqlalchemy.orm import Session

# ── Attempt to load the trained ML models ─────────────────────────
_BASE_DIR = os.path.dirname(os.path.abspath(__file__))

_ml_classifier = None
_ml_regressor = None
_ml_label_encoder = None
_ml_available = False

try:
    import joblib

    _classifier_path = os.path.join(_BASE_DIR, "triage_classifier.joblib")
    _regressor_path = os.path.join(_BASE_DIR, "triage_regressor.joblib")
    _encoder_path = os.path.join(_BASE_DIR, "triage_label_encoder.joblib")

    if (os.path.exists(_classifier_path)
            and os.path.exists(_regressor_path)
            and os.path.exists(_encoder_path)):
        _ml_classifier = joblib.load(_classifier_path)
        _ml_regressor = joblib.load(_regressor_path)
        _ml_label_encoder = joblib.load(_encoder_path)
        _ml_available = True
        print("[triage] ML model loaded successfully (Random Forest)")
    else:
        print("[triage] ML model files not found — using formula fallback")

except ImportError:
    print("[triage] joblib/scikit-learn not installed — using formula fallback")
except Exception as e:
    print(f"[triage] Error loading ML model: {e} — using formula fallback")


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
    """Original TA-CSS weighted-sum formula (used when ML model is unavailable)."""
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

    trend = get_trend(db, vitals.soldier_id, vitals.recorded_at)
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
    """Use the trained Random Forest model for prediction."""
    # Build feature vector: [hr, spo2, activity_index, respiratory_rate, blast_severity]
    blast_severity = getattr(vitals, "blast_severity", None) or 0.0

    features = np.array([[
        float(vitals.hr),
        float(vitals.spo2),
        float(vitals.activity_index),
        float(vitals.respiratory_rate),
        float(blast_severity),
    ]])

    # Predict classification
    class_encoded = _ml_classifier.predict(features)[0]
    classification = _ml_label_encoder.inverse_transform([class_encoded])[0]

    # Predict severity score
    score = float(_ml_regressor.predict(features)[0])

    return {"score": round(score, 4), "classification": str(classification)}


# ── Public API ────────────────────────────────────────────────────
def calculate_score(vitals, db: Session) -> Optional[Dict[str, float]]:
    """Calculate the triage severity score for a single Vitals record.

    Uses the trained ML model if available, otherwise falls back to the
    original weighted-sum formula.

    Parameters
    ----------
    vitals : VitalsModel row just saved by vitals.py.
    db : active SQLAlchemy session.

    Returns
    -------
    dict | None
        ``{"score": <float>, "classification": <str>}``, or None if the
        reading doesn't have enough fields to score.
    """
    if vitals.hr is None or vitals.spo2 is None:
        return None
    if vitals.activity_index is None or vitals.respiratory_rate is None:
        return None

    # ── Use ML model if available, otherwise formula fallback ─────
    if _ml_available:
        result = _ml_predict(vitals)
    else:
        result = _formula_fallback(vitals, db)

    # Persist onto the row — caller (vitals.py) commits.
    vitals.score = result["score"]
    vitals.classification = result["classification"]

    return result