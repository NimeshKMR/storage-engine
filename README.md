# Java Storage Engine + Storage Engine Explorer

A page-based storage engine in Java (`com.nimeshkmr.storage`) with a B+ tree index, a write-ahead log (WAL)
and crash recovery, plus a **Streamlit visual explorer** that shows the real engine at work.

## What's in the engine

- **Pages**: 4096-byte pages on disk. Page 0 is the metadata page (magic `0x42505431`, root page ID).
- **B+ tree** (`BPlusTree`): `int` keys and values, insert and search. Leaf capacity 510, internal capacity 340,
  linked leaves. Leaf and internal splits, and root growth.
- **Write-ahead log** (`WriteAheadLog`, `LogRecord`): CRC32-protected page-image records and commit records.
- **Transactions** (`PageWriteTransaction`): `ACTIVE → COMMIT_UNCERTAIN → COMMITTED → APPLIED` (or `ABORTED`).
- **Recovery** (`RecoveryManager`): replays page images of transactions that have a commit record.
- **Concurrency**: `ReentrantReadWriteLock` around tree operations.

## Repository layout

```
pom.xml                          Maven build (Java 21, JUnit 5)
src/main/java/.../storage/       The engine
src/main/java/.../storage/explorer/   Local HTTP adapter used by the explorer (JDK HttpServer only)
src/test/java/...                Engine tests + explorer tests
visualizer/                      Streamlit UI, start scripts, Python tests, detailed docs
```

## Build and test

```bash
mvn test                                       # all Java tests
python -m pip install -r visualizer/requirements-dev.txt
python -m pytest visualizer/tests              # Python tests (start the real Java server themselves)
```

Without Maven, the start script compiles with plain `javac` into `target/classes`.

## Run the explorer

Two terminals, from this folder:

```bash
python -m venv .venv && source .venv/bin/activate      # Windows: .venv\Scripts\Activate.ps1
pip install -r visualizer/requirements.txt

visualizer/start-backend.sh      # Windows: .\visualizer\start-backend.ps1   -> http://127.0.0.1:8787
visualizer/start-ui.sh           # Windows: .\visualizer\start-ui.ps1        -> http://localhost:8501
```

Everything the UI shows comes from the real Java engine over a local API; the UI contains no storage logic and
shows an explicit "backend unavailable" screen rather than sample data.

## Quick demo

1. Sidebar: create database `demo` with **Demo fan-out (4/4)**.
2. **B+ tree** tab: bulk insert keys 1-14, then insert 15. "What the engine did" reports a leaf split, an internal split and a new root.
3. Search a key to see the root-to-leaf path highlighted; search a missing key to see where it would go.
4. **Pages** and **Write-ahead log** tabs: inspect the physical pages and the WAL records.
5. **Recovery demo** tab: run the four scenarios (on a temporary database that is discarded).
6. Close and reopen `demo` to confirm persistence.

## Changes made to the engine (all additive)

- `BPlusTree(dm, wal, leafCapacity, internalCapacity)`: optional smaller node capacity for demos. Existing constructors and the 510/340 defaults are unchanged.
- `BPlusTree.insertWithReport(key, value)` returns an `InsertReport` (insert vs update, splits, separators, new pages, root change, WAL transaction). `insert` behaves as before.
- `BPlusTree.readConsistently(...)`, `isRecoveryRequired()`, `getLeafCapacity()`, `getInternalCapacity()`.
- `PageWriteTransaction.getTransactionID()` / `getStagedPageIDs()`; `LeafNode` / `InternalNode` constructors taking a capacity.

## Safety

- The API binds to `127.0.0.1` only, checks the `Host` header, requires an `X-Explorer-Client` header on non-GET requests, and validates database names.
- Nothing is deleted automatically. Deleting a database needs an explicit confirmation (typing its name in the UI).
- The explorer only touches its own `explorer-data/` folder, never `database.db`.

## Status and limitations

- Verified here: 61 Java tests (via a `javac` harness, **not** Maven) and 29 Python tests pass. `mvn test` has not been run by the author of this change, so run it on your machine.
- The PowerShell scripts have not been executed.
- Insert and search only (the engine has no delete). Keys and values are 32-bit integers.
- The recovery demo is a file-level simulation of four crash states, not a real crash or power cut.

See [`visualizer/README.md`](visualizer/README.md) for the full API, configuration and limitations.