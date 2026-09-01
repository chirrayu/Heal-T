"""train_model.py

Trains a Random Forest classifier + regressor on the synthetic triage dataset
and saves the trained models as .joblib files for use by triage.py at runtime.

The classifier predicts the triage classification (Stable / Serious / Critical).
The regressor predicts the numeric severity score (continuous 0-~20 range).

Usage:
    python train_model.py
    # → triage_classifier.joblib
    # → triage_regressor.joblib
    # → prints accuracy report
"""

import os
import sys
import numpy as np

# ── Check dependencies ────────────────────────────────────────────
try:
    import joblib
    from sklearn.model_selection import train_test_split
    from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor
    from sklearn.metrics import classification_report, accuracy_score, mean_absolute_error
    from sklearn.preprocessing import LabelEncoder
except ImportError:
    print("ERROR: scikit-learn and joblib are required.")
    print("Install with: pip install scikit-learn joblib")
    sys.exit(1)


def load_dataset(path: str):
    """Load CSV dataset using numpy (avoids pandas dependency)."""
    # Read header
    with open(path, "r") as f:
        header = f.readline().strip().split(",")

    # Read data
    data = np.genfromtxt(path, delimiter=",", skip_header=1, dtype=None, encoding="utf-8")

    # Extract features and labels
    features = []
    scores = []
    labels = []

    for row in data:
        features.append([
            float(row[0]),  # hr
            float(row[1]),  # spo2
            float(row[2]),  # activity_index
            float(row[3]),  # respiratory_rate
            float(row[4]),  # blast_severity
        ])
        scores.append(float(row[5]))    # score
        labels.append(str(row[6]))      # classification

    return np.array(features), np.array(scores), np.array(labels), header


def main():
    base_dir = os.path.dirname(__file__) or "."
    dataset_path = os.path.join(base_dir, "triage_dataset.csv")

    if not os.path.exists(dataset_path):
        print(f"ERROR: Dataset not found at {dataset_path}")
        print("Run 'python generate_dataset.py' first.")
        sys.exit(1)

    print("=" * 60)
    print("  ML TRIAGE MODEL TRAINING")
    print("=" * 60)

    # ── Load data ─────────────────────────────────────────────────
    print("\n[1/5] Loading dataset...")
    X, y_score, y_label, header = load_dataset(dataset_path)
    print(f"      Loaded {len(X):,} samples with {X.shape[1]} features")
    print(f"      Features: hr, spo2, activity_index, respiratory_rate, blast_severity")

    # ── Encode labels ─────────────────────────────────────────────
    le = LabelEncoder()
    y_class_encoded = le.fit_transform(y_label)
    class_names = le.classes_
    print(f"      Classes: {list(class_names)}")

    # ── Train/test split ──────────────────────────────────────────
    print("\n[2/5] Splitting data (80% train / 20% test)...")
    X_train, X_test, y_train_cls, y_test_cls, y_train_score, y_test_score = (
        train_test_split(X, y_class_encoded, y_score, test_size=0.2, random_state=42, stratify=y_class_encoded)
    )
    print(f"      Train: {len(X_train):,} samples")
    print(f"      Test:  {len(X_test):,} samples")

    # ── Train classifier ──────────────────────────────────────────
    print("\n[3/5] Training Random Forest Classifier...")
    classifier = RandomForestClassifier(
        n_estimators=100,
        max_depth=15,
        min_samples_split=5,
        min_samples_leaf=2,
        random_state=42,
        n_jobs=-1,
        class_weight="balanced"   # handles any class imbalance
    )
    classifier.fit(X_train, y_train_cls)

    # Evaluate classifier
    y_pred_cls = classifier.predict(X_test)
    accuracy = accuracy_score(y_test_cls, y_pred_cls)
    print(f"      Accuracy: {accuracy:.4f} ({accuracy*100:.1f}%)")
    print()
    print("      Classification Report:")
    report = classification_report(
        y_test_cls, y_pred_cls,
        target_names=class_names,
        digits=3
    )
    for line in report.split("\n"):
        print(f"      {line}")

    # Feature importance
    importances = classifier.feature_importances_
    feature_names = ["hr", "spo2", "activity_index", "respiratory_rate", "blast_severity"]
    print("\n      Feature Importance:")
    for name, imp in sorted(zip(feature_names, importances), key=lambda x: -x[1]):
        bar = "#" * int(imp * 40)
        print(f"        {name:20s}: {imp:.4f} {bar}")

    # ── Train regressor ───────────────────────────────────────────
    print("\n[4/5] Training Random Forest Regressor (severity score)...")
    regressor = RandomForestRegressor(
        n_estimators=100,
        max_depth=15,
        min_samples_split=5,
        min_samples_leaf=2,
        random_state=42,
        n_jobs=-1
    )
    regressor.fit(X_train, y_train_score)

    y_pred_score = regressor.predict(X_test)
    mae = mean_absolute_error(y_test_score, y_pred_score)
    print(f"      Mean Absolute Error: {mae:.4f}")

    # ── Save models ───────────────────────────────────────────────
    print("\n[5/5] Saving trained models...")

    classifier_path = os.path.join(base_dir, "triage_classifier.joblib")
    regressor_path = os.path.join(base_dir, "triage_regressor.joblib")
    encoder_path = os.path.join(base_dir, "triage_label_encoder.joblib")

    joblib.dump(classifier, classifier_path)
    joblib.dump(regressor, regressor_path)
    joblib.dump(le, encoder_path)

    cls_size = os.path.getsize(classifier_path) / 1024
    reg_size = os.path.getsize(regressor_path) / 1024
    enc_size = os.path.getsize(encoder_path) / 1024

    print(f"      [OK] Classifier:    {classifier_path} ({cls_size:.1f} KB)")
    print(f"      [OK] Regressor:     {regressor_path} ({reg_size:.1f} KB)")
    print(f"      [OK] Label Encoder: {encoder_path} ({enc_size:.1f} KB)")

    print("\n" + "=" * 60)
    print(f"  TRAINING COMPLETE — Accuracy: {accuracy*100:.1f}%")
    print("=" * 60)
    print("\nThe models are ready. Restart the backend server to use them.")
    print("triage.py will automatically detect and load the .joblib files.")


if __name__ == "__main__":
    main()
