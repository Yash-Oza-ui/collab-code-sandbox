"""Evaluate baseline anomaly detector on held-out synthetic data (T-15).

Usage:
    python scripts/evaluate_baseline.py --data data.csv --seed 42
    python scripts/evaluate_baseline.py --test-data models/test_set.csv
"""

import argparse
import os
import sys
from pathlib import Path
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.metrics import (
    roc_auc_score,
    average_precision_score,
    precision_score,
    recall_score,
    f1_score,
    confusion_matrix,
)

# Ensure ai-ml-service root is in sys.path
SCRIPT_DIR = Path(__file__).resolve().parent
SERVICE_ROOT = SCRIPT_DIR.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.ml.scorer import AnomalyScorer


def load_held_out_data(data_path: str = None, test_data_path: str = None, seed: int = 42):
    """Load held-out evaluation dataset either from test_set.csv or by splitting data.csv."""
    if test_data_path and os.path.exists(test_data_path):
        print(f"Loading held-out test set directly from: {test_data_path}")
        return pd.read_csv(test_data_path)

    if data_path and os.path.exists(data_path):
        print(f"Loading dataset from: {data_path} (splitting 80/20 with seed={seed})")
        df = pd.read_csv(data_path)
        if "label" not in df.columns:
            raise ValueError(f"Dataset at {data_path} is missing the required 'label' column.")
        _, test_df = train_test_split(df, test_size=0.2, random_state=seed)
        return test_df

    default_test = os.path.join(SERVICE_ROOT, "models/test_set.csv")
    if os.path.exists(default_test):
        print(f"Loading default held-out test set from: {default_test}")
        return pd.read_csv(default_test)

    raise FileNotFoundError(
        "No evaluation data found. Provide --data <dataset.csv> or run scripts/train_baseline.py first."
    )


def evaluate_model(
    data_path: str = None,
    test_data_path: str = None,
    model_path: str = None,
    seed: int = 42,
    threshold: float = 0.50,
) -> dict:
    """Evaluate the baseline anomaly model on held-out synthetic samples."""
    resolved_model_path = model_path or os.path.join(SERVICE_ROOT, "models/baseline_anomaly_model.joblib")
    if not os.path.exists(resolved_model_path):
        raise FileNotFoundError(
            f"Trained model not found at '{resolved_model_path}'. "
            "Please run 'python scripts/train_baseline.py --data <data.csv> --seed 42' first."
        )

    test_df = load_held_out_data(data_path=data_path, test_data_path=test_data_path, seed=seed)
    scorer = AnomalyScorer(model_path=resolved_model_path)

    print(f"Scoring {len(test_df)} held-out synthetic runs...")
    scores = []
    for _, row in test_df.iterrows():
        score = scorer.score_run(row.to_dict())
        scores.append(score)

    y_true = test_df["label"].values.astype(int)
    scores = pd.Series(scores)

    # Threshold-independent metrics
    roc_auc = float(roc_auc_score(y_true, scores))
    pr_auc = float(average_precision_score(y_true, scores))

    # Reference metrics at default normalized threshold
    y_pred = (scores >= threshold).astype(int)
    prec = float(precision_score(y_true, y_pred, zero_division=0))
    rec = float(recall_score(y_true, y_pred, zero_division=0))
    f1 = float(f1_score(y_true, y_pred, zero_division=0))
    cm = confusion_matrix(y_true, y_pred).tolist()

    # Score distribution by class
    normal_scores = scores[y_true == 0]
    anom_scores = scores[y_true == 1]

    results = {
        "samples_evaluated": len(test_df),
        "normal_samples": int(len(normal_scores)),
        "anomalous_samples": int(len(anom_scores)),
        "roc_auc": round(roc_auc, 4),
        "pr_auc": round(pr_auc, 4),
        "threshold": threshold,
        "precision": round(prec, 4),
        "recall": round(rec, 4),
        "f1": round(f1, 4),
        "confusion_matrix": cm,
        "normal_mean_score": round(float(normal_scores.mean()), 4),
        "anomalous_mean_score": round(float(anom_scores.mean()), 4),
        "score_separation_margin": round(float(anom_scores.mean() - normal_scores.mean()), 4),
    }

    # Sanity check: verify separation between normal and anomalous
    sanity_passed = results["anomalous_mean_score"] > results["normal_mean_score"]
    results["sanity_check_passed"] = sanity_passed

    print("\n" + "=" * 60)
    print("BASELINE ANOMALY MODEL EVALUATION REPORT (T-15)")
    print("=" * 60)
    print(f"Dataset: Held-out synthetic test set ({results['samples_evaluated']} samples)")
    print(f"Class distribution: {results['normal_samples']} Normal / {results['anomalous_samples']} Anomalous")
    print("-" * 60)
    print(f"ROC-AUC:               {results['roc_auc']:.4f}")
    print(f"PR-AUC:                {results['pr_auc']:.4f}")
    print(f"Precision (@ {threshold}):    {results['precision']:.4f}")
    print(f"Recall (@ {threshold}):       {results['recall']:.4f}")
    print(f"F1-Score (@ {threshold}):     {results['f1']:.4f}")
    print(f"Confusion Matrix:      {results['confusion_matrix']}")
    print("-" * 60)
    print(f"Normal Mean Score:     {results['normal_mean_score']:.4f}")
    print(f"Anomalous Mean Score:  {results['anomalous_mean_score']:.4f}")
    print(f"Separation Margin:     {results['score_separation_margin']:+.4f}")
    print(f"Directional Sanity:    {'PASSED' if sanity_passed else 'FAILED'}")
    print("=" * 60)
    print("DISCLAIMER: Evaluated exclusively on synthetic data generated via T-13.")
    print("Results validate baseline class separation and do not reflect real usage.")
    print("=" * 60 + "\n")

    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Evaluate baseline anomaly model on held-out synthetic data.")
    parser.add_argument("--data", type=str, default=None, help="Path to T-12 synthetic dataset CSV")
    parser.add_argument("--test-data", type=str, default=None, help="Path to held-out test CSV")
    parser.add_argument("--model", type=str, default=None, help="Path to saved .joblib model")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for evaluation split")
    parser.add_argument("--threshold", type=float, default=0.50, help="Reference threshold for binary metrics")
    args = parser.parse_args()

    evaluate_model(
        data_path=args.data,
        test_data_path=args.test_data,
        model_path=args.model,
        seed=args.seed,
        threshold=args.threshold,
    )
