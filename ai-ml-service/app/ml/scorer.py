import joblib
import os
import pandas as pd
from typing import Dict, Any

class AnomalyScorer:
    """Loads the baseline model and scores individual execution runs."""
    
    REQUIRED_FEATURES = [
        "execution_time_ms", 
        "peak_memory_mb", 
        "stdout_bytes", 
        "stderr_bytes", 
        "exit_code", 
        "timed_out", 
        "oom_killed"
    ]
    
    def __init__(self, model_path: str = None):
        if model_path is None:
            # Resolve model path relative to this file to avoid working directory mismatches in tests
            base_dir = os.path.dirname(os.path.abspath(__file__))
            model_path = os.path.join(base_dir, "../../models/baseline_anomaly_model.joblib")
            
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"Model not found at {model_path}. Run training script first.")
        self.model = joblib.load(model_path)
        
    def score_run(self, metrics: Dict[str, Any]) -> float:
        missing = [f for f in self.REQUIRED_FEATURES if f not in metrics]
        if missing:
            raise ValueError(f"Missing required execution metric keys for anomaly scoring: {missing}")
        
        features = {
            "execution_time_ms": float(metrics["execution_time_ms"]),
            "peak_memory_mb": float(metrics["peak_memory_mb"]),
            "stdout_bytes": int(metrics["stdout_bytes"]),
            "stderr_bytes": int(metrics["stderr_bytes"]),
            "exit_code": int(metrics["exit_code"]),
            "timed_out": int(metrics["timed_out"]),
            "oom_killed": int(metrics["oom_killed"])
        }
        
        df = pd.DataFrame([features])
        raw_score = self.model.decision_function(df)[0]
        normalized_score = 0.5 - (raw_score / 2.0) 
        
        return max(0.0, min(1.0, float(normalized_score)))