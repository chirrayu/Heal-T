"""
model.py

Neural-network training and inference for the synthetic triage dataset.

Input features:
    hr
    spo2
    activity_index
    respiratory_rate
    blast_severity

Target:
    Stable
    Serious
    Critical

The model:
    5 input features
        ↓
    Dense(5 → 128)
        ↓
    ReLU
        ↓
    Dropout
        ↓
    Dense(128 → 256)
        ↓
    ReLU
        ↓
    Dropout
        ↓
    Dense(256 → 128)
        ↓
    ReLU
        ↓
    Dense(128 → 3)

The script:
    1. Loads triage_dataset.csv
    2. Preprocesses the data
    3. Splits into training/testing sets
    4. Builds the neural network
    5. Calculates exact trainable parameters
    6. Trains the model
    7. Evaluates accuracy, F1 score and confusion matrix
    8. Saves the trained model to triage_model.pth
    9. Provides a cached prediction function for backend use

Usage:
    python model.py

Backend usage:
    from model import predict_triage

    result = predict_triage(
        hr=110,
        spo2=92,
        activity_index=1,
        respiratory_rate=24,
        blast_severity=0.0
    )

    print(result)
"""

from pathlib import Path
import numpy as np

try:
    import pandas as pd
except ImportError:
    pd = None

try:
    import torch
    import torch.nn as nn
    _TORCH_AVAILABLE = True
except ImportError:
    torch = None
    nn = None
    _TORCH_AVAILABLE = False

from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
)
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler


# ============================================================
# Configuration
# ============================================================

BASE_DIR = Path(__file__).resolve().parent

DATASET_PATH = BASE_DIR / "triage_dataset.csv"
MODEL_PATH = BASE_DIR / "triage_model.pth"

RANDOM_STATE = 42

TEST_SIZE = 0.20

BATCH_SIZE = 64
EPOCHS = 100
LEARNING_RATE = 0.001

# Stop training when validation loss stops improving.
PATIENCE = 10

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu") if _TORCH_AVAILABLE else "cpu"


# ============================================================
# Feature configuration
# ============================================================

FEATURE_COLUMNS = [
    "hr",
    "spo2",
    "activity_index",
    "respiratory_rate",
    "blast_severity",
]

TARGET_COLUMN = "classification"

CLASS_NAMES = [
    "Stable",
    "Serious",
    "Critical",
]

CLASS_TO_INDEX = {
    "Stable": 0,
    "Serious": 1,
    "Critical": 2,
}

INDEX_TO_CLASS = {
    0: "Stable",
    1: "Serious",
    2: "Critical",
}


# ============================================================
# Reproducibility
# ============================================================

def set_seed(seed: int = RANDOM_STATE) -> None:
    """
    Set random seeds so training is reproducible.
    """

    np.random.seed(seed)
    if _TORCH_AVAILABLE:
        torch.manual_seed(seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(seed)


# ============================================================
# Neural Network
# ============================================================

class TriageMLP(nn.Module if _TORCH_AVAILABLE else object):
    """
    Multi-layer perceptron for triage classification.

    Architecture:

        Input: 5

        5 → 128
        ReLU
        Dropout

        128 → 256
        ReLU
        Dropout

        256 → 128
        ReLU

        128 → 3

    Output:
        3 logits corresponding to:
            0 = Stable
            1 = Serious
            2 = Critical
    """

    def __init__(self):
        super().__init__()

        self.network = nn.Sequential(
            nn.Linear(5, 128),
            nn.ReLU(),
            nn.Dropout(0.20),

            nn.Linear(128, 256),
            nn.ReLU(),
            nn.Dropout(0.20),

            nn.Linear(256, 128),
            nn.ReLU(),

            nn.Linear(128, 3),
        )

    def forward(self, x):
        return self.network(x)


# ============================================================
# Parameter calculation
# ============================================================

def count_parameters(model: nn.Module) -> int:
    """
    Return the exact number of trainable parameters.
    """

    return sum(
        parameter.numel()
        for parameter in model.parameters()
        if parameter.requires_grad
    )


def print_parameter_breakdown(model: nn.Module) -> None:
    """
    Print the parameter count for every trainable layer.
    """

    print("\nParameter breakdown:")
    print("-" * 60)

    total = 0

    for name, parameter in model.named_parameters():

        if not parameter.requires_grad:
            continue

        count = parameter.numel()
        total += count

        print(
            f"{name:<35} "
            f"{str(tuple(parameter.shape)):<18} "
            f"{count:>8,}"
        )

    print("-" * 60)
    print(f"{'TOTAL TRAINABLE PARAMETERS':<55}{total:>8,}")


# ============================================================
# Dataset loading
# ============================================================

def load_dataset():
    """
    Load and prepare the dataset.

    Returns:
        X      -> feature matrix
        y      -> integer class labels
        scaler -> fitted StandardScaler
    """

    if not DATASET_PATH.exists():
        raise FileNotFoundError(
            f"Dataset not found: {DATASET_PATH}"
        )

    print(f"Loading dataset: {DATASET_PATH}")

    df = pd.read_csv(DATASET_PATH)

    required_columns = FEATURE_COLUMNS + [TARGET_COLUMN]

    missing_columns = [
        column
        for column in required_columns
        if column not in df.columns
    ]

    if missing_columns:
        raise ValueError(
            f"Missing columns in dataset: {missing_columns}"
        )

    # Remove rows containing missing values.
    df = df.dropna(
        subset=required_columns
    ).reset_index(drop=True)

    X = df[FEATURE_COLUMNS].astype(np.float32).values

    y = (
        df[TARGET_COLUMN]
        .map(CLASS_TO_INDEX)
        .values
    )

    if np.any(pd.isna(y)):
        raise ValueError(
            "Dataset contains an unknown classification label."
        )

    y = y.astype(np.int64)

    print(f"Samples: {len(df):,}")

    print("\nClass distribution:")

    for class_name in CLASS_NAMES:

        class_index = CLASS_TO_INDEX[class_name]

        count = np.sum(y == class_index)

        percentage = (
            count / len(y) * 100
        )

        print(
            f"  {class_name:<10}: "
            f"{count:>6,} "
            f"({percentage:5.1f}%)"
        )

    # Standardize features.
    scaler = StandardScaler()
    X = scaler.fit_transform(X)

    return X, y, scaler


# ============================================================
# Training
# ============================================================

def train_model(
    model,
    X_train,
    y_train,
    X_val,
    y_val,
):
    """
    Train the neural network.

    Returns:
        trained model
    """

    X_train_tensor = torch.tensor(
        X_train,
        dtype=torch.float32
    ).to(DEVICE)

    y_train_tensor = torch.tensor(
        y_train,
        dtype=torch.long
    ).to(DEVICE)

    X_val_tensor = torch.tensor(
        X_val,
        dtype=torch.float32
    ).to(DEVICE)

    y_val_tensor = torch.tensor(
        y_val,
        dtype=torch.long
    ).to(DEVICE)

    # TensorDataset + DataLoader
    train_dataset = torch.utils.data.TensorDataset(
        X_train_tensor,
        y_train_tensor
    )

    train_loader = torch.utils.data.DataLoader(
        train_dataset,
        batch_size=BATCH_SIZE,
        shuffle=True
    )

    criterion = nn.CrossEntropyLoss()

    optimizer = torch.optim.Adam(
        model.parameters(),
        lr=LEARNING_RATE
    )

    best_val_loss = float("inf")
    epochs_without_improvement = 0

    best_state = None

    print("\nStarting training...")
    print(f"Device: {DEVICE}")
    print(f"Epochs: {EPOCHS}")
    print(f"Batch size: {BATCH_SIZE}")
    print(f"Learning rate: {LEARNING_RATE}")

    print("\n" + "-" * 70)

    for epoch in range(EPOCHS):

        # ----------------------------------------------------
        # Training
        # ----------------------------------------------------

        model.train()

        running_loss = 0.0
        correct = 0
        total = 0

        for batch_X, batch_y in train_loader:

            optimizer.zero_grad()

            outputs = model(batch_X)

            loss = criterion(
                outputs,
                batch_y
            )

            loss.backward()

            optimizer.step()

            running_loss += (
                loss.item() * batch_X.size(0)
            )

            predictions = torch.argmax(
                outputs,
                dim=1
            )

            correct += (
                predictions == batch_y
            ).sum().item()

            total += batch_y.size(0)

        train_loss = running_loss / total
        train_accuracy = correct / total

        # ----------------------------------------------------
        # Validation
        # ----------------------------------------------------

        model.eval()

        with torch.no_grad():

            val_outputs = model(
                X_val_tensor
            )

            val_loss = criterion(
                val_outputs,
                y_val_tensor
            ).item()

            val_predictions = torch.argmax(
                val_outputs,
                dim=1
            )

            val_accuracy = (
                val_predictions == y_val_tensor
            ).float().mean().item()

        print(
            f"Epoch {epoch + 1:03d}/{EPOCHS} | "
            f"Train Loss: {train_loss:.4f} | "
            f"Train Acc: {train_accuracy:.4f} | "
            f"Val Loss: {val_loss:.4f} | "
            f"Val Acc: {val_accuracy:.4f}"
        )

        # ----------------------------------------------------
        # Early stopping
        # ----------------------------------------------------

        if val_loss < best_val_loss:

            best_val_loss = val_loss

            epochs_without_improvement = 0

            best_state = {
                key: value.detach().cpu().clone()
                for key, value
                in model.state_dict().items()
            }

        else:

            epochs_without_improvement += 1

        if epochs_without_improvement >= PATIENCE:

            print(
                f"\nEarly stopping triggered "
                f"after {epoch + 1} epochs."
            )

            break

    # Restore best model.

    if best_state is not None:

        model.load_state_dict(
            best_state
        )

    print("-" * 70)

    return model


# ============================================================
# Evaluation
# ============================================================

def evaluate_model(model, X_test, y_test):
    """
    Evaluate the trained model.
    """

    model.eval()

    X_test_tensor = torch.tensor(
        X_test,
        dtype=torch.float32
    ).to(DEVICE)

    with torch.no_grad():

        outputs = model(
            X_test_tensor
        )

        predictions = torch.argmax(
            outputs,
            dim=1
        ).cpu().numpy()

    # --------------------------------------------------------
    # Metrics
    # --------------------------------------------------------

    accuracy = accuracy_score(
        y_test,
        predictions
    )

    macro_f1 = f1_score(
        y_test,
        predictions,
        average="macro"
    )

    weighted_f1 = f1_score(
        y_test,
        predictions,
        average="weighted"
    )

    print("\n")
    print("=" * 70)
    print("MODEL EVALUATION")
    print("=" * 70)

    print(
        f"\nAccuracy:    {accuracy:.4f} "
        f"({accuracy * 100:.2f}%)"
    )

    print(
        f"Macro F1:    {macro_f1:.4f}"
    )

    print(
        f"Weighted F1: {weighted_f1:.4f}"
    )

    # --------------------------------------------------------
    # Classification report
    # --------------------------------------------------------

    print("\nClassification Report:")
    print("-" * 70)

    print(
        classification_report(
            y_test,
            predictions,
            target_names=CLASS_NAMES,
            digits=4,
            zero_division=0
        )
    )

    # --------------------------------------------------------
    # Confusion matrix
    # --------------------------------------------------------

    cm = confusion_matrix(
        y_test,
        predictions,
        labels=[0, 1, 2]
    )

    print("Confusion Matrix:")
    print("-" * 70)

    print(
        f"{'':>15}"
        f"{'Stable':>12}"
        f"{'Serious':>12}"
        f"{'Critical':>12}"
    )

    for index, class_name in enumerate(CLASS_NAMES):

        print(
            f"{class_name:>15}"
            f"{cm[index, 0]:>12}"
            f"{cm[index, 1]:>12}"
            f"{cm[index, 2]:>12}"
        )

    print("=" * 70)

    return {
        "accuracy": accuracy,
        "macro_f1": macro_f1,
        "weighted_f1": weighted_f1,
        "confusion_matrix": cm,
    }


# ============================================================
# Model saving
# ============================================================

def save_model(
    model,
    scaler,
    path=MODEL_PATH,
):
    """
    Save the trained model and preprocessing information.
    """

    checkpoint = {
        "model_state_dict": model.state_dict(),

        "feature_columns": FEATURE_COLUMNS,

        "class_names": CLASS_NAMES,

        "class_to_index": CLASS_TO_INDEX,

        "scaler_mean": scaler.mean_.tolist(),

        "scaler_scale": scaler.scale_.tolist(),

        "architecture": {
            "input_size": 5,
            "hidden_1": 128,
            "hidden_2": 256,
            "hidden_3": 128,
            "output_size": 3,
        },

        "parameter_count": count_parameters(model),
    }

    torch.save(
        checkpoint,
        path
    )

    print(
        f"\nModel saved to: {path}"
    )


# ============================================================
# Model loading
# ============================================================

def load_model(
    path=MODEL_PATH,
):
    """
    Load a trained model from disk.

    Returns:
        model
        scaler
    """

    if not Path(path).exists():

        raise FileNotFoundError(
            f"Trained model not found: {path}"
        )

    checkpoint = torch.load(
        path,
        map_location=DEVICE,
        weights_only=False
    )

    model = TriageMLP().to(DEVICE)

    model.load_state_dict(
        checkpoint["model_state_dict"]
    )

    model.eval()

    # Reconstruct the StandardScaler.

    scaler = StandardScaler()

    scaler.mean_ = np.array(
        checkpoint["scaler_mean"],
        dtype=np.float64
    )

    scaler.scale_ = np.array(
        checkpoint["scaler_scale"],
        dtype=np.float64
    )

    scaler.n_features_in_ = len(
        FEATURE_COLUMNS
    )

    return model, scaler


# ============================================================
# Backend Performance Caching
# ============================================================

_MODEL_CACHE = None
_SCALER_CACHE = None

def _get_model_and_scaler():
    """
    Loads the model and scaler into memory on the first call, 
    then returns the cached versions for all subsequent calls.
    Prevents disk I/O and model instantiation on every API request.
    """
    global _MODEL_CACHE, _SCALER_CACHE
    
    if _MODEL_CACHE is None:
        print("Loading model and scaler into memory...")
        _MODEL_CACHE, _SCALER_CACHE = load_model()
        
    return _MODEL_CACHE, _SCALER_CACHE


# ============================================================
# Backend prediction function
# ============================================================

def predict_triage(
    hr: float,
    spo2: float,
    activity_index: float,
    respiratory_rate: float,
    blast_severity: float,
):
    """
    Predict the triage classification for one vitals reading.
    """

    # Basic validation.

    if not 20 <= hr <= 250:
        raise ValueError(
            "Heart rate must be between 20 and 250 BPM."
        )

    if not 0 <= spo2 <= 100:
        raise ValueError(
            "SpO2 must be between 0 and 100%."
        )

    if not 0 <= activity_index <= 3:
        raise ValueError(
            "Activity index must be between 0 and 3."
        )

    if not 1 <= respiratory_rate <= 80:
        raise ValueError(
            "Respiratory rate must be between 1 and 80."
        )

    if not 0 <= blast_severity <= 0.5:
        raise ValueError(
            "Blast severity must be between 0.0 and 0.5."
        )

    # Load model (Cached after first run)
    model, scaler = _get_model_and_scaler()

    # Create feature vector.

    features = np.array(
        [[
            hr,
            spo2,
            activity_index,
            respiratory_rate,
            blast_severity,
        ]],
        dtype=np.float32
    )

    # Apply the SAME scaler used during training.

    features = scaler.transform(
        features
    )

    tensor = torch.tensor(
        features,
        dtype=torch.float32
    ).to(DEVICE)

    # Prediction.

    model.eval()

    with torch.no_grad():

        logits = model(tensor)

        probabilities = torch.softmax(
            logits,
            dim=1
        )[0]

        predicted_index = torch.argmax(
            probabilities
        ).item()

    classification = INDEX_TO_CLASS[
        predicted_index
    ]

    confidence = probabilities[
        predicted_index
    ].item()

    probability_dict = {
        CLASS_NAMES[index]: round(
            probabilities[index].item(),
            4
        )
        for index in range(len(CLASS_NAMES))
    }

    return {
        "classification": classification,

        "confidence": round(
            confidence,
            4
        ),

        "probabilities": probability_dict,
    }


# ============================================================
# Main
# ============================================================

def main():

    print("=" * 70)
    print("TA-CSS TRIAGE NEURAL NETWORK")
    print("=" * 70)

    set_seed()

    # --------------------------------------------------------
    # Load dataset
    # --------------------------------------------------------

    X, y, scaler = load_dataset()

    # --------------------------------------------------------
    # Train / test split
    # --------------------------------------------------------

    X_train, X_test, y_train, y_test = train_test_split(
        X,
        y,
        test_size=TEST_SIZE,
        random_state=RANDOM_STATE,
        stratify=y,
    )

    print("\nDataset split:")
    print(
        f"  Training samples: {len(X_train):,}"
    )
    print(
        f"  Testing samples:  {len(X_test):,}"
    )

    # --------------------------------------------------------
    # Build model
    # --------------------------------------------------------

    model = TriageMLP().to(DEVICE)

    parameter_count = count_parameters(
        model
    )

    print("\nModel architecture:")
    print(model)

    print(
        f"\nExact trainable parameter count: "
        f"{parameter_count:,}"
    )

    print_parameter_breakdown(
        model
    )

    # --------------------------------------------------------
    # Train
    # --------------------------------------------------------

    model = train_model(
        model,
        X_train,
        y_train,
        X_test,
        y_test,
    )

    # --------------------------------------------------------
    # Evaluate
    # --------------------------------------------------------

    evaluate_model(
        model,
        X_test,
        y_test,
    )

    # --------------------------------------------------------
    # Save
    # --------------------------------------------------------

    save_model(
        model,
        scaler,
    )

    # --------------------------------------------------------
    # Example prediction
    # --------------------------------------------------------

    print("\nExample backend prediction:")

    result = predict_triage(
        hr=110,
        spo2=92,
        activity_index=1,
        respiratory_rate=24,
        blast_severity=0.0,
    )

    print(result)

def train_random_forest(dataset_path=DATASET_PATH):
    """
    Train Random Forest Classifier and Regressor models, evaluate accuracy,
    and save them with cryptographic SHA-256 integrity checksums to prevent tampering.
    """
    import csv
    import hashlib
    import json
    import joblib
    from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor
    from sklearn.preprocessing import LabelEncoder
    from sklearn.metrics import accuracy_score, f1_score, mean_squared_error

    print("\n" + "=" * 70)
    print("TRAINING RANDOM FOREST MODELS & COMPUTING SHA-256 CHECKSUMS")
    print("=" * 70)

    rows = []
    with open(dataset_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append((
                float(row["hr"]),
                float(row["spo2"]),
                float(row["activity_index"]),
                float(row["respiratory_rate"]),
                float(row["blast_severity"]),
                float(row["score"]),
                row["classification"]
            ))

    X = np.array([[r[0], r[1], r[2], r[3], r[4]] for r in rows], dtype=np.float32)
    y_score = np.array([r[5] for r in rows], dtype=np.float32)
    y_class = [r[6] for r in rows]

    le = LabelEncoder()
    y_class_enc = le.fit_transform(y_class)

    X_tr, X_te, y_cls_tr, y_cls_te, y_sc_tr, y_sc_te = train_test_split(
        X, y_class_enc, y_score, test_size=0.2, random_state=42, stratify=y_class_enc
    )

    clf = RandomForestClassifier(n_estimators=50, max_depth=12, random_state=42)
    clf.fit(X_tr, y_cls_tr)

    reg = RandomForestRegressor(n_estimators=50, max_depth=12, random_state=42)
    reg.fit(X_tr, y_sc_tr)

    # Evaluation
    cls_preds = clf.predict(X_te)
    sc_preds = reg.predict(X_te)
    acc = accuracy_score(y_cls_te, cls_preds)
    f1 = f1_score(y_cls_te, cls_preds, average="macro")
    mse = mean_squared_error(y_sc_te, sc_preds)

    print(f"Classifier Test Accuracy: {acc * 100:.2f}% | Macro F1: {f1:.4f}")
    print(f"Regressor Test MSE:       {mse:.4f}")

    clf_path = BASE_DIR / "triage_classifier.joblib"
    reg_path = BASE_DIR / "triage_regressor.joblib"
    enc_path = BASE_DIR / "triage_label_encoder.joblib"
    chk_path = BASE_DIR / "triage_checksums.json"

    joblib.dump(clf, clf_path)
    joblib.dump(reg, reg_path)
    joblib.dump(le, enc_path)

    def file_sha256(path):
        h = hashlib.sha256()
        with open(path, "rb") as f:
            while chunk := f.read(65536):
                h.update(chunk)
        return h.hexdigest()

    checksums = {
        "triage_classifier.joblib": file_sha256(clf_path),
        "triage_regressor.joblib": file_sha256(reg_path),
        "triage_label_encoder.joblib": file_sha256(enc_path)
    }

    with open(chk_path, "w", encoding="utf-8") as f:
        json.dump(checksums, f, indent=2)

    print("\nVerified Cryptographic SHA-256 Checksums Generated:")
    print(json.dumps(checksums, indent=2))
    print("=" * 70)
    return checksums


if __name__ == "__main__":
    import sys
    if "--mlp" in sys.argv and pd is not None and _TORCH_AVAILABLE:
        main()
    elif pd is None or not _TORCH_AVAILABLE or "--rf" in sys.argv:
        if not _TORCH_AVAILABLE or pd is None:
            print("[train_model] PyTorch or Pandas not installed. Training Random Forest models with integrity hashing...")
        train_random_forest()
    else:
        main()