#!/usr/bin/env bash
# Starts the Streamlit UI. Start the backend first (start-backend.sh).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${EXPLORER_PORT:-8787}"
export STORAGE_EXPLORER_URL="${STORAGE_EXPLORER_URL:-http://127.0.0.1:$PORT}"
PY="${PYTHON:-python3}"
"$PY" -c "import streamlit, requests" 2>/dev/null || {
  echo "Missing Python packages. Run:  $PY -m pip install -r \"$HERE/requirements.txt\"" >&2; exit 1; }
cd "$HERE"
exec "$PY" -m streamlit run app.py
