import joblib
import os
import pandas as pd
from typing import Dict, Any

class AnomalyScorer:
    """Loads the baseline model and scores individual execution runs."""
    
    def __init__(self, model_path: str = "models/baseline_anomaly_model.joblib"):
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"Model not found at {model_path}. Run training script first.")
        self.model = joblib.load(model_path)
        
    def score_run(self, metrics: Dict[str, Any]) -> float:
        """
        Takes a dictionary of execution metrics and returns an anomaly score.
        Higher score = more anomalous.
        """
        # Extract the exact features used during training
        features = {
            "execution_time_ms": metrics.get("execution_time_ms", 0.0),
            "peak_memory_mb": metrics.get("peak_memory_mb", 0.0),
            "stdout_bytes": metrics.get("stdout_bytes", 0),
            "stderr_bytes": metrics.get("stderr_bytes", 0),
            "exit_code": metrics.get("exit_code", 0),
            "timed_out": int(metrics.get("timed_out", False)),
            "oom_killed": int(metrics.get("oom_killed", False))
        }
        
        # Convert to DataFrame for the scikit-learn pipeline
        df = pd.DataFrame([features])
        
        # Isolation forest returns scores where lower is more anomalous (negative).
        # We invert it so higher = more anomalous, scaling roughly to [0, 1] for our contract.
        raw_score = self.model.decision_function(df)[0]
        normalized_score = 0.5 - (raw_score / 2.0) 
        
        # Ensure it stays within standard bounds
        return max(0.0, min(1.0, float(normalized_score)))

# Singleton instance to be imported by the router in Sprint 2
# baseline_scorer = AnomalyScorer() # Intentionally commented out until T-15 evaluation is done