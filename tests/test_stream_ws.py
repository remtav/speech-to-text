"""Tests of the WebSocket route /ws/stream (issue #134)."""
import time

import pytest
from starlette.websockets import WebSocketDisconnect

SECOND = bytes(32000)  # 1 s of PCM silence (16 kHz, 16 bits, mono)
START = {"type": "start", "language": "fr", "languages": ["fr", "en"], "prompt": ""}


def drain(ws):
    """Messages up to and including the first `final` or `error`."""
    out = []
    while True:
        message = ws.receive_json()
        out.append(message)
        if message["type"] in ("final", "error"):
            return out


def test_ready_then_final(make_client):
    client = make_client()
    with client.websocket_connect("/ws/stream") as ws:
        ws.send_json(START)
        ready = ws.receive_json()
        assert ready == {"type": "ready", "step_ms": 1000}
        for _ in range(3):
            ws.send_bytes(SECOND)
        ws.send_json({"type": "stop"})
        messages = drain(ws)
        assert messages[-1] == {"type": "final", "text": "w0 w1 w2"}
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
        assert closed.value.code == 1000


def test_updates_while_streaming(make_client):
    client = make_client(stream_step=0.25)
    with client.websocket_connect("/ws/stream") as ws:
        ws.send_json(START)
        ws.receive_json()
        for _ in range(3):
            ws.send_bytes(SECOND)
            time.sleep(0.7)
        ws.send_json({"type": "stop"})
        messages = drain(ws)
    updates = [m for m in messages if m["type"] == "update"]
    assert updates and messages[-1]["type"] == "final"
    lengths = [len(m["committed"]) for m in updates]
    assert lengths == sorted(lengths)
    assert updates[-1]["audio_ms"] > 0


def test_language_detected_once(make_client):
    client = make_client(stream_step=0.25)
    with client.websocket_connect("/ws/stream") as ws:
        ws.send_json({"type": "start", "language": None})
        ws.receive_json()
        for _ in range(3):
            ws.send_bytes(SECOND)
            time.sleep(0.7)
        ws.send_json({"type": "stop"})
        drain(ws)
    passes = [c for c in client.engine.calls if c[0] == "stream_decode"]
    assert len(passes) >= 2
    assert not passes[0][2].get("language")
    assert all(c[2]["language"] == "fr" for c in passes[1:])


def test_token_required(make_client):
    token = "t" * 20
    client = make_client(auth_required=True, tokens=(token,))
    with pytest.raises(WebSocketDisconnect) as closed:
        with client.websocket_connect("/ws/stream"):
            pass
    assert closed.value.code == 1008
    with pytest.raises(WebSocketDisconnect) as closed:
        with client.websocket_connect("/ws/stream", headers={"Authorization": "Bearer wrong"}):
            pass
    assert closed.value.code == 1008
    with client.websocket_connect("/ws/stream", headers={"Authorization": f"Bearer {token}"}) as ws:
        ws.send_json(START)
        assert ws.receive_json()["type"] == "ready"


def test_max_sessions(make_client):
    client = make_client(stream_max_sessions=1)
    with client.websocket_connect("/ws/stream") as first:
        first.send_json(START)
        first.receive_json()
        with pytest.raises(WebSocketDisconnect) as closed:
            with client.websocket_connect("/ws/stream"):
                pass
        assert closed.value.code == 1013
    # the slot is free again once the first session is closed
    time.sleep(0.2)
    with client.websocket_connect("/ws/stream") as again:
        again.send_json(START)
        assert again.receive_json()["type"] == "ready"


def test_disabled(make_client):
    client = make_client(stream_max_sessions=0)
    with pytest.raises(WebSocketDisconnect) as closed:
        with client.websocket_connect("/ws/stream"):
            pass
    assert closed.value.code == 1013


def test_rate_limit(make_client):
    token = "t" * 20
    client = make_client(auth_required=True, tokens=(token,), rate_limit=1)
    headers = {"Authorization": f"Bearer {token}"}
    with client.websocket_connect("/ws/stream", headers=headers) as ws:
        ws.send_json(START)
        ws.receive_json()
    with pytest.raises(WebSocketDisconnect) as closed:
        with client.websocket_connect("/ws/stream", headers=headers):
            pass
    assert closed.value.code == 1013


@pytest.mark.parametrize("start", [{"type": "autre"}, {"type": "start", "language": "xx"},
                                   {"type": "start", "languages": ["fr", "zz"]},
                                   {"type": "start", "prompt": "a" * 1001},
                                   {"type": "start", "languages": "fr"}])
def test_bad_start(make_client, start):
    client = make_client()
    with client.websocket_connect("/ws/stream") as ws:
        ws.send_json(start)
        assert ws.receive_json()["type"] == "error"
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
        assert closed.value.code == 1003


def test_max_seconds(make_client):
    client = make_client(stream_max_seconds=10)
    with client.websocket_connect("/ws/stream") as ws:
        ws.send_json(START)
        ws.receive_json()
        for _ in range(11):
            ws.send_bytes(SECOND)
        messages = []
        with pytest.raises(WebSocketDisconnect) as closed:
            while True:
                messages.append(ws.receive_json())
        assert closed.value.code == 1009
    assert messages[-1]["type"] == "error"


def test_engine_error(make_client):
    client = make_client()

    def boom(*a, **k):
        raise RuntimeError("secret user text")

    client.engine.stream_decode = boom
    with client.websocket_connect("/ws/stream") as ws:
        ws.send_json(START)
        ws.receive_json()
        ws.send_bytes(SECOND)
        ws.send_json({"type": "stop"})
        message = drain(ws)[-1]
        assert message["type"] == "error" and "secret" not in message["message"]
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
        assert closed.value.code == 1011


def test_passes_not_in_jobs(make_client):
    client = make_client()
    with client.websocket_connect("/ws/stream") as ws:
        ws.send_json(START)
        ws.receive_json()
        ws.send_bytes(SECOND)
        ws.send_json({"type": "stop"})
        drain(ws)
    assert client.get("/jobs").json() == {"jobs": []}
    assert client.get("/progress").json()["job_id"] is None
