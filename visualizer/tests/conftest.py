"""Shared fixtures. Tests that need engine data start the real Java server; none use fabricated state."""
from __future__ import annotations

import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]          # project root (contains pom.xml)
VIS = ROOT / "visualizer"
sys.path.insert(0, str(VIS))

from explorer_client import BackendUnavailable, ExplorerClient  # noqa: E402


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _find_classes(tmp: Path) -> Path:
    """Compiled engine classes: STORAGE_EXPLORER_CLASSES, else target/classes, else compile with javac."""
    env = os.environ.get("STORAGE_EXPLORER_CLASSES")
    if env and Path(env).is_dir():
        return Path(env)
    target = ROOT / "target" / "classes"
    if (target / "com" / "nimeshkmr" / "storage" / "explorer" / "ExplorerServer.class").exists():
        return target
    if not shutil.which("javac"):
        pytest.skip("No compiled engine classes (run `mvn compile`) and no javac available")
    out = tmp / "classes"
    out.mkdir()
    sources = [str(p) for p in (ROOT / "src" / "main" / "java").rglob("*.java")]
    proc = subprocess.run(["javac", "-nowarn", "-d", str(out), *sources], capture_output=True, text=True)
    if proc.returncode != 0:
        pytest.fail("javac failed:\n" + proc.stderr)
    return out


class JavaServer:
    def __init__(self, classes: Path, data_dir: Path):
        self.port = _free_port()
        self.url = f"http://127.0.0.1:{self.port}"
        self.data_dir = data_dir
        self.proc = subprocess.Popen(
            ["java", "-cp", str(classes), "com.nimeshkmr.storage.explorer.ExplorerServer",
             "--port", str(self.port), "--data-dir", str(data_dir)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        client = ExplorerClient(self.url)
        deadline = time.time() + 20
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError("Java server exited early:\n" + (self.proc.stdout.read() or ""))
            try:
                client.health()
                return
            except BackendUnavailable:
                time.sleep(0.2)
        self.stop()
        raise RuntimeError("Java server did not start in time")

    def stop(self) -> None:
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()


@pytest.fixture(scope="session")
def java_server():
    if not shutil.which("java"):
        pytest.skip("java is not installed")
    tmp = Path(tempfile.mkdtemp(prefix="explorer-pytest-"))
    server = None
    try:
        classes = _find_classes(tmp)
        server = JavaServer(classes, tmp / "data")
        server.classes = classes
        yield server
    finally:
        if server:
            server.stop()
        shutil.rmtree(tmp, ignore_errors=True)


@pytest.fixture()
def client(java_server):
    return ExplorerClient(java_server.url)


@pytest.fixture()
def db_name(client):
    """A fresh, uniquely named demo database (fan-out 4/4), deleted afterwards."""
    name = "t" + str(time.time_ns())[-10:]
    client.create(name, 4, 4)
    yield name
    try:
        client.delete(name, name)
    except Exception:
        pass


@pytest.fixture()
def fresh_server(java_server, tmp_path):
    """A second, empty Java server (own data directory) for tests that need a pristine state."""
    server = JavaServer(java_server.classes, tmp_path / "data")
    try:
        yield server
    finally:
        server.stop()
