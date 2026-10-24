#!/usr/bin/env bash
# Builds the Java engine and starts the Explorer API on 127.0.0.1.
# Never deletes anything. Databases live in EXPLORER_DATA_DIR (default: <project>/explorer-data).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${EXPLORER_PORT:-8787}"
DATA="${EXPLORER_DATA_DIR:-$ROOT/explorer-data}"
CLASSES="$ROOT/target/classes"
cd "$ROOT"

command -v java >/dev/null || { echo "java (JDK 21+) not found on PATH" >&2; exit 1; }

built=0
if command -v mvn >/dev/null 2>&1; then
  echo "Building with Maven..."
  if mvn -q -B compile; then built=1; else echo "Maven build failed; falling back to javac." >&2; fi
fi
if [ "$built" -eq 0 ]; then
  command -v javac >/dev/null || { echo "Neither a working Maven nor javac is available" >&2; exit 1; }
  echo "Building with javac into $CLASSES ..."
  mkdir -p "$CLASSES"
  javac -nowarn -d "$CLASSES" $(find src/main/java -name '*.java')
fi

echo "Starting Storage Explorer API on http://127.0.0.1:$PORT  (data: $DATA)"
exec java -cp "$CLASSES" com.nimeshkmr.storage.explorer.ExplorerServer --port "$PORT" --data-dir "$DATA"
