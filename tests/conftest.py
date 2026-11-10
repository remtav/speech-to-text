import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).parent.parent))

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.worker import InlineBackend  # noqa: E402
from tests.fake_engine import FakeEngine  # noqa: E402
from tests.fake_polish import FakePolishClient  # noqa: E402


@pytest.fixture(autouse=True)
def isolated_hf_token(monkeypatch, tmp_path):
    """Never read or write the developer's real Hugging Face token."""
    monkeypatch.delenv("HF_TOKEN", raising=False)
    monkeypatch.delenv("HUGGING_FACE_HUB_TOKEN", raising=False)
    constants = pytest.importorskip("huggingface_hub.constants")
    path = tmp_path / "hf" / "token"
    monkeypatch.setattr(constants, "HF_TOKEN_PATH", str(path))
    return path


@pytest.fixture
def make_client():
    """Build a test client around a model-free engine and a fake LLM, with lifespan started."""
    clients = []

    def factory(raise_server_exceptions=True, polish_client=None, **overrides):
        settings = Settings(**{"preload": False, "worker_mode": "inline", **overrides})
        engine = FakeEngine()
        polish_client = polish_client or FakePolishClient()
        client = TestClient(create_app(settings, backend=InlineBackend(engine), polish_client=polish_client),
                            raise_server_exceptions=raise_server_exceptions)
        client.__enter__()
        client.engine = engine
        client.polish_client = polish_client
        clients.append(client)
        return client

    yield factory
    for client in clients:
        client.__exit__(None, None, None)


@pytest.fixture
def client(make_client):
    return make_client()
