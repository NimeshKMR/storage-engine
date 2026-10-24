# Storage Engine Explorer

A browser tool for watching your Java storage engine work: insert keys, search with the path highlighted,
see leaf/internal/root splits explained from what the engine reported, inspect physical pages, read the
write-ahead log, and run controlled recovery demonstrations.

Everything on screen comes from the **real Java engine** over a small local HTTP API. The Streamlit app holds
no copy of the tree and implements no storage logic. If the backend is down you get an explicit
"backend unavailable" screen, never sample data.

```
 Browser ──▶ Streamlit (Python, visualizer/app.py)
                 │  HTTP/JSON, 127.0.0.1 only
                 ▼
             ExplorerServer (Java, JDK built-in HttpServer)
                 │
             ExplorerService ─▶ BPlusTree + WriteAheadLog + DiskManager   (your engine)
                 │
             explorer-data/  <name>.db  <name>.wal  <name>.explorer.properties
```

## Prerequisites

- JDK 21+ (the project targets Java 21). Maven is optional: the start script falls back to `javac`.
- Python 3.10+ with `pip`.

## Quick start

Two terminals, from the project root (the folder containing `pom.xml`).

```bash
# 1) Python dependencies (once; a virtualenv is recommended)
python -m venv .venv && source .venv/bin/activate        # Windows: .venv\Scripts\Activate.ps1
pip install -r visualizer/requirements.txt

# 2) Terminal A: build + start the Java API  (http://127.0.0.1:8787)
visualizer/start-backend.sh        # Windows PowerShell: .\visualizer\start-backend.ps1

# 3) Terminal B: start the UI  (http://localhost:8501)
visualizer/start-ui.sh             # Windows PowerShell: .\visualizer\start-ui.ps1
```

Manual equivalents: `mvn -q compile`, then
`java -cp target/classes com.nimeshkmr.storage.explorer.ExplorerServer --port 8787 --data-dir explorer-data`,
and `streamlit run visualizer/app.py`.

### Configuration

| Setting | Default | Meaning |
|---|---|---|
| `EXPLORER_PORT` | `8787` | Java API port (both start scripts read it) |
| `EXPLORER_DATA_DIR` | `<project>/explorer-data` | Where demo databases are stored |
| `STORAGE_EXPLORER_URL` | `http://127.0.0.1:<EXPLORER_PORT>` | Where the UI looks for the API (also editable in the sidebar) |
| `PYTHON` | `python3` / `python` | Interpreter used by `start-ui` |

## Using it

1. **Create a database** (sidebar). *Demo fan-out* (4 keys per node) makes splits happen after a few inserts,
   so a 3-level tree needs ~15 keys instead of ~90,000. *Engine default* uses your unchanged 510/340 limits.
2. **B+ tree tab**: *Insert* (the key defaults to the next free key) and read *What the engine did*:
   update vs insert, each split with its separator and new page, root changes, pages appended, and the WAL
   transaction. *Search* highlights the root-to-leaf path and the key's slot, or the slot where a missing key would go.
   *Bulk insert* runs many real inserts. *Before / after* shows engine snapshots taken around the operation.
   Wide trees are drawn as a window of leaves; "Follow last operation" keeps the changed area in view.
3. **Pages tab**: every physical page (role, depth, key count, reachable or not), page contents with leaf
   values, child ranges, the metadata page (magic number, root page ID) and an optional hex preview.
4. **Write-ahead log tab**: page-image and commit records in log order, with pagination. Page images are summarised, not dumped.
5. **Recovery demo tab**: four scenarios run on a temporary database that is thrown away. The page shows what
   the simulation does and does not do, the files before recovery, which pages recovery changed (measured by
   comparing page bytes), and the result.

### Reset and delete (never silent)

Nothing is ever deleted automatically: starting, restarting or reopening only reads existing files, and
`open` refuses to create missing files. To remove a database use **Delete database** in the sidebar (you must
type its name) or `DELETE /api/databases/{name}?confirm={name}`. To reset everything, stop the backend and
delete the `explorer-data` folder yourself. The explorer never touches `./database.db` or any file outside its data directory.

## HTTP API (local, JSON)

`GET /api/health` · `GET|POST /api/databases` · `GET /api/databases/{n}` · `POST …/open` · `POST …/close` ·
`DELETE …?confirm=` · `POST …/insert?key=&value=` · `POST …/bulk` · `GET …/search?key=` · `GET …/tree` ·
`GET …/pages` · `GET …/pages/{id}?hex=true` · `GET …/wal?offset=&limit=` · `GET|POST /api/recovery-demo`.
Errors are `{"error":{"code","message"}}` with 400/403/404/405/409/500. Full list: `ExplorerServer` Javadoc.

Safety model: binds to 127.0.0.1 only, rejects non-loopback `Host` headers, requires an `X-Explorer-Client`
header on every non-GET request (a web page cannot add it cross-origin), validates database names
(`[a-z0-9][a-z0-9_-]{0,31}`), and only opens databases it created itself. It has no authentication; it is a local demo tool.

## Changes to the engine (all additive)

- `BPlusTree(dm, wal, leafCapacity, internalCapacity)`: optional smaller node capacity for demos. Existing
  constructors and the 510/340 defaults are unchanged. **Capacities are not stored in the database file**;
  the explorer records them in a `.explorer.properties` file next to each database.
- `BPlusTree.insertWithReport(key, value)` → `InsertReport`: what the insert did (insert vs update, splits,
  separators, new pages, root change, WAL transaction). `insert` behaves as before.
- `BPlusTree.readConsistently(...)`: runs a read-only callback under the tree's read lock, so snapshots never see a half-applied insert.
- `BPlusTree.isRecoveryRequired()`, `PageWriteTransaction.getTransactionID()/getStagedPageIDs()`,
  `LeafNode/InternalNode` constructors taking a capacity.

## Tests

```bash
mvn test                                  # Java: your suites + InsertReportTest + explorer/*Test
python -m pip install -r visualizer/requirements-dev.txt
python -m pytest visualizer/tests         # Python: client, renderer, explain, and AppTest end-to-end
```

The Python end-to-end tests start the real Java server themselves (using `target/classes`, or compiling with `javac` if absent).

## Known limitations

- Local, single-process demo. Not a statement about production durability, multi-process safety or full crash safety.
- Keys and values are 32-bit integers (the engine's types). Insert and search only; the engine has no delete.
- "Committed" is only claimed where the engine's own state says so (transaction state `APPLIED`; "commit
  record present in log" in the WAL view). Whether storage honours `force()` is outside the engine's control.
- The recovery demo is a **file-level simulation**: after a normal run it edits the closed files to recreate one on-disk state
  (e.g. WAL intact, database rolled back; commit record missing or torn). It does not kill the JVM or cut power,
  and covers four crash points, not all of them.
- Tree nodes are not clickable (a Streamlit iframe cannot report clicks); pick pages in the Pages tab instead.
- The WAL view pages forward from the start; there is no jump-to-end. The log is never truncated by the engine.
- Pages allocated by an insert that did not complete stay in the file (shown as "not reachable").
- Opening a database with different capacities than it was built with is unsupported and is why the explorer remembers them.
- `BufferPool` is not used by `BPlusTree`, so it does not appear in the explorer.
- The PowerShell scripts mirror the bash scripts but have not been executed by the author of this change.
