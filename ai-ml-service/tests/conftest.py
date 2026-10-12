import os
import pytest
import subprocess

@pytest.fixture(scope="session", autouse=True)
def ensure_model_exists():
    """Automatically generate data and train the baseline model if it doesn't exist (for CI environments)."""
    # Look for the model relative to the ai-ml-service root
    base_dir = os.path.dirname(os.path.abspath(__file__))
    model_path = os.path.join(base_dir, "../models/baseline_anomaly_model.joblib")
    
    if not os.path.exists(model_path):
        print("\n[CI Setup] Model not found. Generating synthetic dataset and training baseline model...")
        service_dir = os.path.dirname(base_dir)
        
        # Run generator and trainer programmatically
        subprocess.run(["python", "generate_dataset.py", "--out", "data.csv"], cwd=service_dir, check=True)
        subprocess.run(["python", "scripts/train_baseline.py", "--data", "data.csv", "--seed", "42"], cwd=service_dir, check=True)
        print("[CI Setup] Model training complete. Proceeding with tests.\n")