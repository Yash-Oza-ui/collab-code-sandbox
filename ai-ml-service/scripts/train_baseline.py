import argparse
import pandas as pd
import joblib
import os
from sklearn.ensemble import IsolationForest
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

def train_model(data_path: str, seed: int):
    print(f"Loading synthetic dataset from {data_path}...")
    df = pd.read_csv(data_path)
    
    # Use the numeric features defined in T-13
    features = [
        "execution_time_ms", 
        "peak_memory_mb", 
        "stdout_bytes", 
        "stderr_bytes", 
        "exit_code", 
        "timed_out", 
        "oom_killed"
    ]
    
    X = df[features].copy()
    
    # Convert booleans to integers
    X["timed_out"] = X["timed_out"].astype(int)
    X["oom_killed"] = X["oom_killed"].astype(int)
    
    # Split train/held-out reproducibly
    X_train, X_test = train_test_split(X, test_size=0.2, random_state=seed)
    
    print(f"Training Isolation Forest on {len(X_train)} samples...")
    
    # Pipeline: Standardize features, then apply Isolation Forest
    model = Pipeline([
        ('scaler', StandardScaler()),
        ('isolation_forest', IsolationForest(
            random_state=seed, 
            n_estimators=100,
            contamination="auto" # Let the model estimate the anomaly proportion
        ))
    ])
    
    model.fit(X_train)
    
    # Save the model
    os.makedirs("models", exist_ok=True)
    model_path = "models/baseline_anomaly_model.joblib"
    joblib.dump(model, model_path)
    print(f"Model saved successfully to {model_path}.")
    
    # Save test set for Prince's evaluation script
    test_path = "models/test_set.csv"
    df.loc[X_test.index].to_csv(test_path, index=False)
    print(f"Held-out test set saved to {test_path} for evaluation.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train baseline anomaly model.")
    parser.add_argument("--data", type=str, required=True, help="Path to T-12 synthetic CSV")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    args = parser.parse_args()
    
    train_model(args.data, args.seed)