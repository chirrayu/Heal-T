"""generate_dataset.py

Generates a synthetic labelled training dataset for the ML triage classifier.

Each row represents one vitals reading with five features:
    hr, spo2, activity_index, respiratory_rate, blast_severity

Labels (score + classification) are computed using the *existing* TA-CSS
weighted-sum formula so the ML model learns the same clinical logic —
but in a data-driven way that can later be fine-tuned on real patient data.

Usage:
    python generate_dataset.py          # → triage_dataset.csv  (~10 000 rows)
    python generate_dataset.py 50000    # → 50 000 rows
"""

import csv
import random
import sys
import os

# ── Replicate the TA-CSS formula from triage.py ───────────────────
W1 = 0.8   # Heart Rate
W2 = 1.3   # SpO2
W3 = 0.9   # Activity Index
W4 = 1.2   # Respiratory Rate
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


def _rr_score(rr: int) -> int:
    if 12 <= rr <= 20:
        return 0
    if 21 <= rr <= 24 or 9 <= rr <= 11:
        return 1
    if 25 <= rr <= 30 or 6 <= rr <= 8:
        return 2
    return 3


def compute_score(hr, spo2, activity_index, respiratory_rate, blast_severity):
    """Replicates the TA-CSS formula (triage.py + blast.py)."""
    hr_sub = _hr_score(hr)
    spo2_sub = _spo2_score(spo2)
    act_sub = activity_index  # already 0-3
    rr_sub = _rr_score(respiratory_rate)

    weighted_sum = (
        W1 * hr_sub +
        W2 * spo2_sub +
        W3 * act_sub +
        W4 * rr_sub
    )

    # Blast multiplier B = 1 + blast_severity (blast_severity ∈ [0, 0.5])
    B = 1.0 + blast_severity

    # Blood loss index L is set to 0 here — in a real scenario it depends
    # on trend data (delta_hr, delta_spo2 over time), which we can't
    # meaningfully simulate row-by-row.  The model will learn the core
    # vitals→severity mapping; blood-loss is a contextual multiplier that
    # the backend can still apply post-prediction if needed.
    L = 0.0

    score = B * weighted_sum + GAMMA * L
    return round(score, 4)


def classify(score: float) -> str:
    if score <= 6.5:
        return "Stable"
    elif score <= 13.5:
        return "Serious"
    else:
        return "Critical"


# ── Sampling distributions ────────────────────────────────────────
# These ranges are intentionally wide so the model sees the full
# clinical spectrum, including edge cases and boundary values.

def random_hr() -> int:
    """Heart rate 30-200 bpm, biased towards normal range."""
    if random.random() < 0.55:
        return random.randint(60, 100)   # normal
    elif random.random() < 0.5:
        return random.randint(40, 130)   # mild–moderate
    else:
        return random.randint(30, 200)   # full range incl. extreme


def random_spo2() -> int:
    """SpO2 50-100%, biased towards normal."""
    if random.random() < 0.50:
        return random.randint(95, 100)
    elif random.random() < 0.5:
        return random.randint(85, 100)
    else:
        return random.randint(50, 100)


def random_activity() -> int:
    return random.randint(0, 3)


def random_rr() -> int:
    """Respiratory rate 4-45 breaths/min."""
    if random.random() < 0.50:
        return random.randint(12, 20)
    elif random.random() < 0.5:
        return random.randint(8, 30)
    else:
        return random.randint(4, 45)


def random_blast() -> float:
    """Blast severity 0.0-0.5; most readings have no blast."""
    if random.random() < 0.80:
        return 0.0
    else:
        return round(random.uniform(0.0, 0.5), 4)


# ── Main ──────────────────────────────────────────────────────────
def main():
    n_samples = int(sys.argv[1]) if len(sys.argv) > 1 else 10_000
    output_path = os.path.join(os.path.dirname(__file__) or ".", "triage_dataset.csv")

    print(f"Generating {n_samples:,} balanced synthetic vitals samples...")

    rows = []
    samples_per_class = n_samples // 3

    # ── Generate STABLE samples (score <= 6.5) ────────────────────
    count = 0
    attempts = 0
    while count < samples_per_class and attempts < samples_per_class * 50:
        attempts += 1
        hr = random.randint(55, 120)
        spo2 = random.randint(90, 100)
        activity = random.randint(0, 2)
        rr = random.randint(10, 24)
        blast = 0.0

        score = compute_score(hr, spo2, activity, rr, blast)
        label = classify(score)
        if label == "Stable":
            rows.append([hr, spo2, activity, rr, blast, score, label])
            count += 1

    # ── Generate SERIOUS samples (6.5 < score <= 13.5) ────────────
    count = 0
    attempts = 0
    while count < samples_per_class and attempts < samples_per_class * 50:
        attempts += 1
        hr = random.randint(50, 170)
        spo2 = random.randint(75, 100)
        activity = random.randint(0, 3)
        rr = random.randint(6, 35)
        blast = round(random.uniform(0.0, 0.3), 4) if random.random() < 0.3 else 0.0

        score = compute_score(hr, spo2, activity, rr, blast)
        label = classify(score)
        if label == "Serious":
            rows.append([hr, spo2, activity, rr, blast, score, label])
            count += 1

    # ── Generate CRITICAL samples (score > 13.5) ──────────────────
    count = 0
    attempts = 0
    while count < samples_per_class and attempts < samples_per_class * 50:
        attempts += 1
        hr = random.randint(30, 200)
        spo2 = random.randint(50, 95)
        activity = random.randint(1, 3)
        rr = random.randint(4, 45)
        blast = round(random.uniform(0.0, 0.5), 4) if random.random() < 0.5 else 0.0

        score = compute_score(hr, spo2, activity, rr, blast)
        label = classify(score)
        if label == "Critical":
            rows.append([hr, spo2, activity, rr, blast, score, label])
            count += 1

    # Shuffle to mix classes
    random.shuffle(rows)

    # Write CSV
    with open(output_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "hr", "spo2", "activity_index", "respiratory_rate",
            "blast_severity", "score", "classification"
        ])
        writer.writerows(rows)

    # Quick stats
    from collections import Counter
    label_counts = Counter(row[6] for row in rows)
    print(f"\nDataset saved to: {output_path}")
    print(f"Total samples: {len(rows):,}")
    print(f"Class distribution:")
    for label in ["Stable", "Serious", "Critical"]:
        count = label_counts.get(label, 0)
        pct = count / len(rows) * 100
        print(f"  {label:10s}: {count:5,} ({pct:5.1f}%)")


if __name__ == "__main__":
    main()
