"""HTTP client for the Java Storage Explorer API.

This module only transports requests and turns failures into typed exceptions. It never
fabricates or caches engine data: every method returns the parsed JSON the Java process sent.
"""
from __future__ import annotations

from typing import Any

import requests

CLIENT_HEADER = "X-Explorer-Client"
DEFAULT_URL = "http://127.0.0.1:8787"


class BackendUnavailable(Exception):
    """The Java API could not be reached (not running, wrong URL, timeout)."""


class ApiError(Exception):
    """The Java API answered with an error (invalid input, unknown database, engine failure...)."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.status = status
        self.code = code
        self.message = message


class ExplorerClient:
    def __init__(self, base_url: str = DEFAULT_URL, timeout: float = 15.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._session = requests.Session()
        self._session.headers[CLIENT_HEADER] = "streamlit"

    # ------------------------------------------------------------------ transport

    def _request(self, method: str, path: str, params: dict | None = None, timeout: float | None = None) -> Any:
        url = self.base_url + path
        clean = {k: v for k, v in (params or {}).items() if v is not None}
        try:
            resp = self._session.request(method, url, params=clean, timeout=timeout or self.timeout)
        except requests.exceptions.ConnectionError as e:
            raise BackendUnavailable(f"Cannot connect to {self.base_url} ({_short(e)})") from e
        except requests.exceptions.Timeout as e:
            raise BackendUnavailable(f"Timed out waiting for {self.base_url}{path}") from e
        except requests.exceptions.RequestException as e:
            raise BackendUnavailable(f"Request to {self.base_url} failed: {_short(e)}") from e
        try:
            body = resp.json()
        except ValueError as e:
            raise ApiError(resp.status_code, "BAD_RESPONSE",
                           f"{self.base_url} did not return JSON (is this the Storage Explorer API?)") from e
        if resp.status_code >= 400:
            err = body.get("error") if isinstance(body, dict) else None
            if isinstance(err, dict):
                raise ApiError(resp.status_code, str(err.get("code", "ERROR")), str(err.get("message", "")))
            raise ApiError(resp.status_code, "ERROR", f"HTTP {resp.status_code}")
        return body

    # ------------------------------------------------------------------ API

    def health(self) -> dict:
        return self._request("GET", "/api/health", timeout=3)

    def list_databases(self) -> list[dict]:
        return self._request("GET", "/api/databases")

    def status(self, name: str) -> dict:
        return self._request("GET", f"/api/databases/{name}")

    def create(self, name: str, leaf_capacity: int, internal_capacity: int) -> dict:
        return self._request("POST", "/api/databases", {
            "name": name, "leafCapacity": leaf_capacity, "internalCapacity": internal_capacity})

    def open(self, name: str) -> dict:
        return self._request("POST", f"/api/databases/{name}/open")

    def close(self, name: str) -> dict:
        return self._request("POST", f"/api/databases/{name}/close")

    def delete(self, name: str, confirm: str) -> dict:
        return self._request("DELETE", f"/api/databases/{name}", {"confirm": confirm})

    def insert(self, name: str, key: int, value: int) -> dict:
        return self._request("POST", f"/api/databases/{name}/insert", {"key": key, "value": value})

    def bulk(self, name: str, start: int, count: int, step: int, order: str, seed: int, multiplier: int) -> dict:
        return self._request("POST", f"/api/databases/{name}/bulk", {
            "start": start, "count": count, "step": step, "order": order,
            "seed": seed, "multiplier": multiplier}, timeout=300)

    def search(self, name: str, key: int) -> dict:
        return self._request("GET", f"/api/databases/{name}/search", {"key": key})

    def tree(self, name: str) -> dict:
        return self._request("GET", f"/api/databases/{name}/tree")

    def pages(self, name: str) -> dict:
        return self._request("GET", f"/api/databases/{name}/pages")

    def page(self, name: str, page_id: int, hex_preview: bool = False) -> dict:
        return self._request("GET", f"/api/databases/{name}/pages/{page_id}",
                             {"hex": "true" if hex_preview else None})

    def wal(self, name: str, offset: int = 0, limit: int = 50) -> dict:
        return self._request("GET", f"/api/databases/{name}/wal", {"offset": offset, "limit": limit})

    def recovery_scenarios(self) -> list[dict]:
        return self._request("GET", "/api/recovery-demo")

    def run_recovery(self, scenario: str) -> dict:
        return self._request("POST", "/api/recovery-demo", {"scenario": scenario}, timeout=120)


def _short(e: Exception) -> str:
    text = str(e)
    return text if len(text) <= 160 else text[:157] + "..."
