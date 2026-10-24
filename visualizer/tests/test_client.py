import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from explorer_client import ApiError, BackendUnavailable, ExplorerClient


# ---- transport behaviour against a throwaway stub (tests the client's error handling only) ----

class _Stub(BaseHTTPRequestHandler):
    seen_headers: dict = {}

    def log_message(self, *a):  # silence
        pass

    def do_GET(self):
        _Stub.seen_headers = dict(self.headers)
        if self.path.startswith("/api/health"):
            self._send(200, b'{"status":"ok"}')
        elif self.path.startswith("/api/databases/missing"):
            self._send(404, json.dumps({"error": {"code": "DATABASE_NOT_FOUND", "message": "nope"}}).encode())
        elif self.path.startswith("/api/databases/html"):
            self._send(200, b"<html>not json</html>")
        elif self.path.startswith("/api/databases/weird"):
            self._send(500, b'{"unexpected": true}')
        else:
            self._send(404, b'{"error":{"code":"NOT_FOUND","message":"x"}}')

    def _send(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


@pytest.fixture()
def stub():
    srv = HTTPServer(("127.0.0.1", 0), _Stub)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def test_unreachable_backend_raises_backend_unavailable():
    c = ExplorerClient("http://127.0.0.1:9", timeout=2)   # port 9 (discard) is not served
    with pytest.raises(BackendUnavailable):
        c.health()


def test_error_json_becomes_api_error(stub):
    with pytest.raises(ApiError) as e:
        ExplorerClient(stub).status("missing")
    assert (e.value.status, e.value.code, e.value.message) == (404, "DATABASE_NOT_FOUND", "nope")


def test_non_json_and_unstructured_errors_are_reported_not_swallowed(stub):
    with pytest.raises(ApiError) as e:
        ExplorerClient(stub).status("html")
    assert e.value.code == "BAD_RESPONSE"
    with pytest.raises(ApiError) as e2:
        ExplorerClient(stub).status("weird")
    assert e2.value.status == 500


def test_client_header_is_sent(stub):
    ExplorerClient(stub).health()
    assert _Stub.seen_headers.get("X-Explorer-Client") == "streamlit"


def test_trailing_slash_in_base_url_is_ignored(stub):
    assert ExplorerClient(stub + "/").health()["status"] == "ok"


# ---- against the real Java server ----

def test_health_reports_engine_limits(client):
    h = client.health()
    assert h["status"] == "ok"
    assert (h["engineLeafCapacity"], h["engineInternalCapacity"]) == (510, 340)


def test_full_round_trip(client, db_name):
    rep = client.insert(db_name, 5, 50)
    assert rep["outcome"] == "INSERTED" and rep["transactionState"] == "APPLIED"
    assert client.search(db_name, 5)["value"] == 50
    assert client.search(db_name, 6)["found"] is False
    assert client.tree(db_name)["keyCount"] == 1


def test_api_errors_from_real_backend(client, db_name):
    with pytest.raises(ApiError) as e:
        client.create("../bad", 4, 4)
    assert (e.value.status, e.value.code) == (400, "INVALID_INPUT")
    with pytest.raises(ApiError) as e:
        client.tree("does-not-exist")
    assert e.value.status == 404
    with pytest.raises(ApiError) as e:
        client.page(db_name, 999)
    assert e.value.code == "PAGE_NOT_FOUND"
    with pytest.raises(ApiError) as e:
        client.delete(db_name, "wrong")
    assert e.value.status == 400
    assert client.status(db_name)["state"] == "OPEN"   # nothing was deleted


def test_persistence_over_close_and_open(client, db_name):
    client.bulk(db_name, 1, 40, 1, "shuffled", 9, 10)
    before = client.tree(db_name)
    client.close(db_name)
    with pytest.raises(ApiError) as e:
        client.tree(db_name)
    assert e.value.code == "NOT_OPEN"
    client.open(db_name)
    assert client.tree(db_name) == before


def test_recovery_scenarios_via_client(client):
    scenarios = client.recovery_scenarios()
    assert [s["id"] for s in scenarios][0] == "clean-reopen" and len(scenarios) == 4
    for s in scenarios:
        r = client.run_recovery(s["id"])
        assert r["outcome"]["matchesExpectation"] is True, s["id"]
