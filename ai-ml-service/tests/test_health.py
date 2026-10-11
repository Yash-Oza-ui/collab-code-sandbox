import sys
from pathlib import Path

# Ensure service root is in sys.path when pytest is invoked directly
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_health_check():
    """Test that GET /health returns HTTP 200 and expected JSON response."""
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
