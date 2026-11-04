"""The running version shown in the UI and /health."""
import json

from app import version


def _with_stamp(monkeypatch, tmp_path, content):
    stamp = tmp_path / "build-info.json"
    stamp.write_text(content)
    monkeypatch.setattr(version, "BUILD_INFO", stamp)
    version.build_info.cache_clear()


def test_build_info_file_wins_over_git(monkeypatch, tmp_path):
    _with_stamp(monkeypatch, tmp_path,
                json.dumps({"commit": "abcdef0123456789", "date": "2026-11-03T10:00:00-05:00"}))
    try:
        info = version.build_info()
        assert (info["commit_short"], info["date"]) == ("abcdef0", "2026-11-03T10:00:00-05:00")
        assert info["url"] == f"{version.REPO_URL}/commit/abcdef0123456789"
        assert version.build_label() == f"v{version.__version__} · abcdef0 · 2026-11-03"
    finally:
        version.build_info.cache_clear()


def test_unknown_commit(monkeypatch, tmp_path):
    _with_stamp(monkeypatch, tmp_path, "{}")  # image built from a source archive, without .git
    try:
        assert version.build_info()["commit"] is None
        assert version.build_info()["url"] == version.REPO_URL
        assert version.build_label() == f"v{version.__version__}"
    finally:
        version.build_info.cache_clear()


def test_page_and_health_show_the_build(client):
    build = version.build_info()
    assert client.get("/health").json()["build"] == build
    page = client.get("/").text
    assert "{{" not in page
    assert version.build_label() in page and build["url"] in page
