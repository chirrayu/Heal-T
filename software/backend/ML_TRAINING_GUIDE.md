# Complete Step-by-Step Machine Learning Training Guide: Wearable Health & Combat Triage System

This document provides an exhaustive, production-grade guide to building, training, evaluating, optimizing, and deploying machine learning models for the Wearable Health & Triage system from scratch.

---

## 📑 Table of Contents
1. [Full Technology Stack & Dependencies](#1-full-technology-stack--dependencies)
2. [Problem Formulation & Mathematical Modeling](#2-problem-formulation--mathematical-modeling)
3. [Environment Setup](#3-environment-setup)
4. [Dataset Creation, Feature Engineering & Augmentation](#4-dataset-creation-feature-engineering--augmentation)
5. [Exploratory Data Analysis (EDA) & Diagnostics](#5-exploratory-data-analysis-eda--diagnostics)
6. [Data Preprocessing & Train/Validation/Test Split](#6-data-preprocessing--trainvalidationtest-split)
7. [Model Selection, Training & Hyperparameter Tuning](#7-model-selection-training--hyperparameter-tuning)
8. [Evaluation, Error Metrics & Diagnostics](#8-evaluation-error-metrics--diagnostics)
9. [Model Serialization & Artifact Storage](#9-model-serialization--artifact-storage)
10. [Production Serving & Real-Time Ingestion Pipeline](#10-production-serving--real-time-ingestion-pipeline)
11. [How to Know When Data Is Insufficient & Retraining Strategies](#11-how-to-know-when-data-is-insufficient--retraining-strategies)

---

## 1. Full Technology Stack & Dependencies

| Tool / Library | Version | Purpose |
| :--- | :--- | :--- |
| **Python** | `>= 3.10` | Core programming language |
| **NumPy** | `>= 1.26.0` | Vectorized multidimensional array math |
| **Pandas** | `>= 2.2.0` | Tabular data manipulation, time-series aggregation, CSV parsing |
| **Scikit-Learn** | `>= 1.4.0` | Core ML algorithms (`RandomForestClassifier`, `RandomForestRegressor`, `StratifiedKFold`, `GridSearchCV`) |
| **Joblib** | `>= 1.3.0` | Zero-copy model persistence and serialization |
| **Matplotlib & Seaborn** | `>= 3.8.0` | Learning curve plots, confusion matrix heatmaps, feature importance charts |
| **SciPy** | `>= 1.12.0` | Statistical distributions and physiological signal filtering |
| **FastAPI + Uvicorn** | `>= 0.115.0` | High-throughput asynchronous backend server for real-time inference |

---

## 2. Problem Formulation & Mathematical Modeling

The triage problem is structured as a **Multi-Task Supervised Learning** problem:

### Input Feature Vector ($\mathbf{X} \in \mathbb{R}^5$)
For any given telemetry packet $t$:
$$\mathbf{x}_t = \begin{bmatrix} \text{HR}_t \\ \text{SpO2}_t \\ \text{Activity}_t \\ \text{RR}_t \\ \text{Blast}_t \end{bmatrix}$$

1. **$\text{HR}$ (Heart Rate)**: Integer in $[30, 220]\text{ BPM}$.
2. **$\text{SpO2}$ (Oxygen Saturation)**: Percentage in $[50, 100]\%$.
3. **$\text{Activity}$ (Activity Index)**: Discrete ordinal $[0, 3]$ (0 = Immobile/Unconscious, 1 = Light, 2 = Moderate, 3 = Heavy Exertion).
4. **$\text{RR}$ (Respiratory Rate)**: Integer in $[4, 50]\text{ breaths/min}$.
5. **$\text{Blast}$ (Blast Severity)**: Continuous float in $[0.0, 0.5]$ normalized from MPU-6050 accelerometer G-force ($3.0\text{G} - 16.0\text{G}$) and shockwave duration ($5\text{ms} - 80\text{ms}$).

### Target Outputs ($y$)
1. **Discrete Classification Band ($y_{\text{class}} \in \{0, 1, 2\}$)**:
   * $0 \rightarrow \text{Critical}$
   * $1 \rightarrow \text{Serious}$
   * $2 \rightarrow \text{Stable}$
2. **Continuous Combat Severity Score ($y_{\text{score}} \in [0.0, 25.0]$)**:
   * Continuous scalar used to prioritize casualties in the evacuation queue.

---

## 3. Environment Setup

Run the following terminal commands to create an isolated Python environment:

```bash
# 1. Create a dedicated virtual environment
python -m venv ml_env

# 2. Activate the virtual environment
# Windows (PowerShell):
.\ml_env\Scripts\Activate.ps1
# Linux / macOS:
source ml_env/bin/activate

# 3. Upgrade pip and install the full ML toolchain
pip install --upgrade pip
pip install numpy pandas scikit-learn joblib matplotlib seaborn fastapi uvicorn
```

---

## 4. Dataset Creation, Feature Engineering & Augmentation

If you do not yet have 100,000 real combat patient records, generate a synthetic clinical dataset with realistic biological constraints and physiological noise:

Create `generate_dataset.py`:

```python
import csv
import random
import numpy as np

def compute_ground_truth(hr, spo2, activity, rr, blast):
    """Clinical heuristic for ground-truth labeling."""
    # Sub-score conversions
    hr_sub = 0 if 60 <= hr <= 100 else (1 if (100 < hr <= 120 or 50 <= hr < 60) else (2 if (120 < hr < 140 or 40 <= hr < 50) else 3))
    spo2_sub = 0 if spo2 >= 95 else (1 if spo2 >= 91 else (2 if spo2 >= 86 else 3))
    rr_sub = 0 if 12 <= rr <= 20 else (1 if (21 <= rr <= 24 or 9 <= rr <= 11) else (2 if (25 <= rr <= 30 or 6 <= rr <= 8) else 3))
    act_sub = activity

    # Weighted sum
    weighted = 0.8 * hr_sub + 1.3 * spo2_sub + 0.9 * act_sub + 1.2 * rr_sub
    multiplier = 1.0 + blast
    score = round(multiplier * weighted, 4)

    # Classification
    if score <= 6.5:
        classification = "Stable"
    elif score <= 13.5:
        classification = "Serious"
    else:
        classification = "Critical"

    return score, classification

def generate_balanced_dataset(filename="triage_dataset.csv", total_samples=15000):
    samples_per_class = total_samples // 3
    rows = []

    # 1. Stable samples (Normal physiological ranges)
    while len([r for r in rows if r[6] == "Stable"]) < samples_per_class:
        hr = random.randint(58, 110)
        spo2 = random.randint(93, 100)
        act = random.randint(0, 2)
        rr = random.randint(12, 22)
        blast = 0.0
        score, label = compute_ground_truth(hr, spo2, act, rr, blast)
        if label == "Stable":
            rows.append([hr, spo2, act, rr, blast, score, label])

    # 2. Serious samples (Elevated distress, blood loss signs, mild blast)
    while len([r for r in rows if r[6] == "Serious"]) < samples_per_class:
        hr = random.randint(50, 160)
        spo2 = random.randint(84, 98)
        act = random.randint(0, 3)
        rr = random.randint(8, 32)
        blast = round(random.uniform(0.0, 0.25), 4) if random.random() < 0.4 else 0.0
        score, label = compute_ground_truth(hr, spo2, act, rr, blast)
        if label == "Serious":
            rows.append([hr, spo2, act, rr, blast, score, label])

    # 3. Critical samples (Severe hypoxia, tachycardia/bradycardia, high blast)
    while len([r for r in rows if r[6] == "Critical"]) < samples_per_class:
        hr = random.randint(30, 200)
        spo2 = random.randint(50, 92)
        act = random.randint(0, 3)
        rr = random.randint(4, 45)
        blast = round(random.uniform(0.1, 0.5), 4) if random.random() < 0.6 else 0.0
        score, label = compute_ground_truth(hr, spo2, act, rr, blast)
        if label == "Critical":
            rows.append([hr, spo2, act, rr, blast, score, label])

    random.shuffle(rows)

    with open(filename, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["hr", "spo2", "activity_index", "respiratory_rate", "blast_severity", "score", "classification"])
        writer.writerows(rows)
    print(f"Generated {len(rows):,} balanced samples in '{filename}'.")

if __name__ == "__main__":
    generate_balanced_dataset()
```

---

## 5. Exploratory Data Analysis (EDA) & Diagnostics

Create `eda_check.py` to verify data balance and distribution correlations:

```python
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt

df = pd.read_csv("triage_dataset.csv")

print("--- Dataset Summary ---")
print(df.info())
print("\n--- Descriptive Statistics ---")
print(df.describe())

print("\n--- Class Distribution ---")
print(df["classification"].value_counts())

# Correlation Matrix
plt.figure(figsize=(8, 6))
numeric_df = df.drop(columns=["classification"])
sns.heatmap(numeric_df.corr(), annot=True, cmap="coolwarm", fmt=".2f")
plt.title("Vital Signs Correlation Matrix")
plt.tight_layout()
plt.savefig("correlation_matrix.png")
print("Saved correlation_matrix.png")
```

---

## 6. Data Preprocessing & Train/Validation/Test Split

Create `train_pipeline.py`:

```python
import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import LabelEncoder

# 1. Load Data
df = pd.read_csv("triage_dataset.csv")

# 2. Extract Features (X) and Targets (y)
feature_cols = ["hr", "spo2", "activity_index", "respiratory_rate", "blast_severity"]
X = df[feature_cols].values
y_class = df["classification"].values
y_score = df["score"].values

# 3. Label Encoding for Multi-Class Output
label_encoder = LabelEncoder()
y_class_encoded = label_encoder.fit_transform(y_class)

# 4. Stratified Train / Test Split (80% Train, 20% Test)
# Stratification ensures each split has the exact same proportion of Critical/Serious/Stable
X_train, X_test, y_train_cls, y_test_cls, y_train_score, y_test_score = train_test_split(
    X, y_class_encoded, y_score,
    test_size=0.20,
    random_state=42,
    stratify=y_class_encoded
)
```

---

## 7. Model Selection, Training & Hyperparameter Tuning

We choose **Random Forest** for:
1. Non-linear boundary learning.
2. Low CPU inference latency ($<1\text{ms}$).
3. Native resistance to overfitting.

```python
from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor
from sklearn.model_selection import GridSearchCV

# --- Step A: Hyperparameter Tuning via 5-Fold Cross Validation ---
param_grid = {
    'n_estimators': [50, 100, 150],
    'max_depth': [10, 15, 20],
    'min_samples_split': [2, 5],
    'min_samples_leaf': [1, 2]
}

print("Tuning Classifier Hyperparameters...")
grid_search = GridSearchCV(
    estimator=RandomForestClassifier(random_state=42, class_weight='balanced'),
    param_grid=param_grid,
    cv=5,
    scoring='f1_macro',
    n_jobs=-1
)
grid_search.fit(X_train, y_train_cls)

best_classifier = grid_search.best_estimator_
print(f"Best Classifier Params: {grid_search.best_params_}")

# --- Step B: Train Regressor for Severity Score ---
regressor = RandomForestRegressor(
    n_estimators=100,
    max_depth=15,
    min_samples_split=5,
    min_samples_leaf=2,
    random_state=42,
    n_jobs=-1
)
regressor.fit(X_train, y_train_score)
```

---

## 8. Evaluation, Error Metrics & Diagnostics

Evaluate the model against holdout test data:

```python
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score, mean_absolute_error

# 1. Classification Metrics
y_pred_cls = best_classifier.predict(X_test)
acc = accuracy_score(y_test_cls, y_pred_cls)

print("\n================ CLASSIFICATION REPORT ================")
print(f"Test Accuracy: {acc * 100:.2f}%\n")
print(classification_report(y_test_cls, y_pred_cls, target_names=label_encoder.classes_))

# 2. Confusion Matrix
print("Confusion Matrix:")
print(confusion_matrix(y_test_cls, y_pred_cls))

# 3. Regression Metrics
y_pred_score = regressor.predict(X_test)
mae = mean_absolute_error(y_test_score, y_pred_score)
print(f"\nSeverity Score Mean Absolute Error (MAE): {mae:.4f}")
```

---

## 9. Model Serialization & Artifact Storage

Save the trained weights to binary files using `joblib`:

```python
import joblib

# Export model binaries
joblib.dump(best_classifier, "triage_classifier.joblib")
joblib.dump(regressor,       "triage_regressor.joblib")
joblib.dump(label_encoder,   "triage_label_encoder.joblib")

print("\nModel binaries saved successfully:")
print(" - triage_classifier.joblib")
print(" - triage_regressor.joblib")
print(" - triage_label_encoder.joblib")
```

---

## 10. Production Serving & Real-Time Ingestion Pipeline

In your FastAPI backend (`triage.py`):

```python
import os
import joblib
import numpy as np

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
classifier = joblib.load(os.path.join(BASE_DIR, "triage_classifier.joblib"))
regressor = joblib.load(os.path.join(BASE_DIR, "triage_regressor.joblib"))
encoder = joblib.load(os.path.join(BASE_DIR, "triage_label_encoder.joblib"))

def predict_vitals(hr: int, spo2: int, activity_index: int, respiratory_rate: int, blast_severity: float = 0.0):
    """Sub-millisecond inference function called on every POST /vitals."""
    feature_vector = np.array([[
        float(hr),
        float(spo2),
        float(activity_index),
        float(respiratory_rate),
        float(blast_severity)
    ]])

    # Inference
    encoded_cls = classifier.predict(feature_vector)[0]
    classification = encoder.inverse_transform([encoded_cls])[0]
    severity_score = float(regressor.predict(feature_vector)[0])

    return {
        "score": round(severity_score, 2),
        "classification": classification
    }
```

---

## 11. How to Know When Data Is Insufficient & Retraining Strategies

| Diagnostic Signal | What It Indicates | Remediation Action |
| :--- | :--- | :--- |
| **Validation Loss Plateau with Low Test Score** | High Variance / Under-sampling | Increase sample size from 10k to 50k+ |
| **Low Recall on 'Critical' Band ($<85\%$)** | Class Imbalance | Apply SMOTE or generate more severe trauma examples |
| **Erratic Cross-Validation Fold Scores ($\sigma > 5\%$)** | High sensitivity to sampling | Expand dataset and enforce stratified k-fold splits |
| **Predictions Fail on Live Hardware** | Sensor noise discrepancy | Add synthetic Gaussian noise to HR/SpO2 features during training |

### Diagnostic Learning Curve Script
Run this script to inspect whether collecting more data will improve accuracy:

```python
import matplotlib.pyplot as plt
from sklearn.model_selection import learning_curve

train_sizes, train_scores, test_scores = learning_curve(
    best_classifier, X, y_class_encoded, cv=5,
    train_sizes=np.linspace(0.1, 1.0, 5), scoring='accuracy'
)

plt.figure()
plt.plot(train_sizes, np.mean(train_scores, axis=1), 'o-', color="r", label="Training Score")
plt.plot(train_sizes, np.mean(test_scores, axis=1), 'o-', color="g", label="Cross-Validation Score")
plt.title("Learning Curves (Triage Model)")
plt.xlabel("Training Examples")
plt.ylabel("Accuracy")
plt.legend(loc="best")
plt.grid(True)
plt.savefig("learning_curve.png")
```
* **Interpretation**: If the green line (Cross-Validation Score) is still rising at the far-right edge, **your model will benefit from more training data**. If it has flattened parallel to the red line, your data quantity is sufficient.
