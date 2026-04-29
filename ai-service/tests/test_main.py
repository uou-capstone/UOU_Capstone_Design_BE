import os

# Set dummy environment variables required for FastAPI startup
os.environ["GEMINI_API_KEY"] = "dummy_api_key_for_testing"

from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

def test_health_endpoint():
    """
    Test the /health endpoint to ensure the application starts up
    and can respond to basic requests.
    """
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    
    assert "status" in data
    assert data["status"] == "ok"
    assert "redis" in data
