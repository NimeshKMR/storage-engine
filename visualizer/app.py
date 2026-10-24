"""Storage Engine Explorer: a Streamlit front end for the Java storage engine.

Every value shown here comes from the Java HTTP API (see explorer_client.py). This file holds no copy
of the tree and implements no storage logic; it only renders what the engine reports.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pandas as pd
import streamlit as st

sys.path.insert(0, str(Path(__file__).parent))

from explain import (changed_pages, describe_insert, describe_search, describe_transaction,  # noqa: E402
                     fmt_ids, structure_status)
from explorer_client import DEFAULT_URL, ApiError, BackendUnavailable, ExplorerClient  # noqa: E402
from tree_svg import render_tree_html  # noqa: E402

INT_MIN, INT_MAX = -(2 ** 31), 2 ** 31 - 1

st.set_page_config(page_title="Storage Engine Explorer", layout="wide", initial_sidebar_state="expanded")

st.markdown("""
<style>
.block-container{padding-top:2.2rem;padding-bottom:2rem;max-width:1500px}
[data-testid="stMetricValue"]{font-size:1.35rem}
[data-testid="stMetricLabel"]{font-size:.78rem;color:#667085}
code{font-size:.85em}
.eng-note{font-size:.85rem;color:#556070}
div[data-testid="stSidebar"] .stButton button{width:100%}
h1{font-size:1.5rem!important;margin-bottom:.2rem}
</style>
""", unsafe_allow_html=True)


# ============================================================ helpers

def init_state() -> None:
    """Defaults for widgets that callbacks also write to (avoids passing a default and setting state)."""
    for k, v in {"ins_key": 1, "ins_val": 10, "srch_key": 1}.items():
        st.session_state.setdefault(k, v)


def get_client() -> ExplorerClient:
    return ExplorerClient(st.session_state.get("backend_url") or DEFAULT_URL)


def embed_html(html: str, height: int) -> None:
    """Embed generated SVG/HTML. The HTML is built only from integers and fixed labels from the engine."""
    if hasattr(st, "iframe"):
        st.iframe(html, height=height)
    else:  # older Streamlit
        import streamlit.components.v1 as components
        components.html(html, height=height, scrolling=True)


def error_record(e: Exception, action: str) -> dict:
    if isinstance(e, BackendUnavailable):
        return {"err": "unavailable", "action": action, "message": str(e)}
    if isinstance(e, ApiError):
        return {"err": "error", "action": action, "code": e.code, "message": e.message}
    raise e


def show_error(rec: dict) -> None:
    if rec["err"] == "unavailable":
        st.error(f"**Java backend unavailable** while trying to {rec['action']}. {rec['message']}\n\n"
                 f"Start it with `visualizer/start-backend.sh` (or `.ps1` on Windows), then retry.")
    else:
        st.error(f"**{rec['code']}**: {rec['message']}  \n_(while trying to {rec['action']})_")


def fetch(fn, *args, action: str):
    """GET helper: returns the result, or shows the error in place and returns None."""
    try:
        return fn(*args)
    except (BackendUnavailable, ApiError) as e:
        show_error(error_record(e, action))
        return None


def set_op(**kw) -> None:
    st.session_state["last_op"] = kw


# ============================================================ callbacks (run before the next rerun)

def cb_create() -> None:
    ss = st.session_state
    preset = ss.get("create_preset", "Demo fan-out (4 keys per node)")
    if preset.startswith("Demo"):
        leaf, internal = 4, 4
    elif preset.startswith("Engine"):
        leaf, internal = 510, 340
    else:
        leaf, internal = int(ss.get("create_leaf", 4)), int(ss.get("create_internal", 4))
    name = (ss.get("create_name") or "").strip()
    try:
        st_ = get_client().create(name, leaf, internal)
        ss["db_select"] = st_["name"]
        ss["create_error"] = None
        set_op(kind="created", db=name, leaf=leaf, internal=internal)
    except (BackendUnavailable, ApiError) as e:
        ss["create_error"] = error_record(e, f"create database '{name}'")


def cb_open(name: str) -> None:
    try:
        get_client().open(name)
        set_op(kind="opened", db=name)
    except (BackendUnavailable, ApiError) as e:
        set_op(kind="failure", db=name, **error_record(e, f"open database '{name}'"))


def cb_close(name: str) -> None:
    try:
        get_client().close(name)
        set_op(kind="closed", db=name)
    except (BackendUnavailable, ApiError) as e:
        set_op(kind="failure", db=name, **error_record(e, f"close database '{name}'"))


def cb_delete(name: str) -> None:
    try:
        get_client().delete(name, st.session_state.get("delete_confirm", ""))
        st.session_state["delete_confirm"] = ""
        set_op(kind="deleted", db=name)
    except (BackendUnavailable, ApiError) as e:
        set_op(kind="failure", db=name, **error_record(e, f"delete database '{name}'"))


def cb_insert(name: str) -> None:
    ss = st.session_state
    key, value = int(ss["ins_key"]), int(ss["ins_val"])
    c = get_client()
    try:
        before = c.tree(name)
        report = c.insert(name, key, value)
        ss["prev_tree"] = {"db": name, "tree": before}
        set_op(kind="insert", db=name, report=report)
        ss["ins_key"] = min(key + 1, INT_MAX)
        ss["ins_val"] = max(INT_MIN, min(INT_MAX, ss["ins_key"] * 10))
    except (BackendUnavailable, ApiError) as e:
        set_op(kind="failure", db=name, **error_record(e, f"insert key {key}"))


def cb_bulk(name: str) -> None:
    ss = st.session_state
    c = get_client()
    try:
        before = c.tree(name)
        result = c.bulk(name, int(ss["bulk_start"]), int(ss["bulk_count"]), int(ss["bulk_step"]),
                        ss["bulk_order"], int(ss["bulk_seed"]), int(ss["bulk_mult"]))
        ss["prev_tree"] = {"db": name, "tree": before}
        set_op(kind="bulk", db=name, result=result)
    except (BackendUnavailable, ApiError) as e:
        set_op(kind="failure", db=name, **error_record(e, "run the bulk insert"))


def cb_search(name: str) -> None:
    key = int(st.session_state["srch_key"])
    try:
        trace = get_client().search(name, key)
        set_op(kind="search", db=name, trace=trace)
    except (BackendUnavailable, ApiError) as e:
        set_op(kind="failure", db=name, **error_record(e, f"search for key {key}"))


def cb_run_recovery(scenario_id: str) -> None:
    try:
        result = get_client().run_recovery(scenario_id)
        st.session_state["recovery_result"] = {"scenario": scenario_id, "result": result, "error": None}
    except (BackendUnavailable, ApiError) as e:
        st.session_state["recovery_result"] = {"scenario": scenario_id, "result": None,
                                               "error": error_record(e, "run the recovery demonstration")}


def cb_wal_offset(value: int) -> None:
    st.session_state["wal_offset"] = value


# ============================================================ sidebar

def sidebar() -> tuple[ExplorerClient, dict | None, list[dict]] | None:
    with st.sidebar:
        st.markdown("### Storage Engine Explorer")
        st.caption("Live view of the Java B+ tree engine")
        st.text_input("Backend URL", value=os.environ.get("STORAGE_EXPLORER_URL", DEFAULT_URL), key="backend_url",
                      help="The Java API process. It listens on 127.0.0.1 only.")
        client = get_client()
        try:
            health = client.health()
        except BackendUnavailable as e:
            st.error("Backend unavailable")
            st.caption(str(e))
            return None
        except ApiError as e:
            st.error(f"Unexpected response: {e}")
            return None
        st.success("Backend connected", icon=None)
        st.caption(f"Data directory: `{health['dataDir']}`")

        try:
            dbs = client.list_databases()
        except (BackendUnavailable, ApiError) as e:
            show_error(error_record(e, "list databases"))
            return None

        names = [d["name"] for d in dbs]
        if st.session_state.get("db_select") not in names:
            st.session_state["db_select"] = names[0] if names else None
        state_of = {d["name"]: d["state"] for d in dbs}
        if names:
            st.selectbox("Database", names, key="db_select",
                         format_func=lambda n: f"{n}  ({state_of[n].lower()})")
        else:
            st.info("No demo databases yet. Create one below.")
        selected = next((d for d in dbs if d["name"] == st.session_state.get("db_select")), None)

        if selected:
            if selected["state"] == "OPEN":
                st.button("Close database", on_click=cb_close, args=(selected["name"],), key="btn_close")
            else:
                st.button("Open database (runs recovery)", on_click=cb_open, args=(selected["name"],),
                          key="btn_open", type="primary")
            st.markdown("**Session**")
            c1, c2 = st.columns(2)
            c1.metric("State", selected["state"].title())
            c2.metric("Root page", "—" if selected["rootPageId"] is None else selected["rootPageId"])
            c1.metric("Pages", "—" if selected["totalPages"] is None else selected["totalPages"])
            c2.metric("Fan-out", f"{selected['leafCapacity']}/{selected['internalCapacity']}",
                      help="Max entries per leaf / max keys per internal node for this database.")
            st.caption(f"database file {selected['dbBytes']:,} B · WAL {selected['walBytes']:,} B")

        with st.expander("Create database", expanded=not names):
            st.text_input("Name", key="create_name", placeholder="e.g. demo",
                          help="Lowercase letters, digits, - and _ (max 32 characters).")
            st.radio("Fan-out", ["Demo fan-out (4 keys per node)", "Engine default (510 / 340)", "Custom"],
                     key="create_preset",
                     help="Demo fan-out makes splits happen after a few inserts. It is a constructor option of "
                          "the real engine; engine-default databases use the unchanged 510/340 limits.")
            if st.session_state.get("create_preset") == "Custom":
                st.number_input("Leaf capacity (3–510)", 3, 510, 4, key="create_leaf")
                st.number_input("Internal capacity (3–340)", 3, 340, 4, key="create_internal")
            st.button("Create and open", on_click=cb_create, key="btn_create")
            if st.session_state.get("create_error"):
                show_error(st.session_state["create_error"])

        if selected:
            with st.expander("Delete database"):
                st.caption(f"Removes {selected['name']}.db, .wal and its settings file. Type the name to confirm.")
                st.text_input("Confirm name", key="delete_confirm")
                st.button("Delete permanently", on_click=cb_delete, args=(selected["name"],), key="btn_delete")
    return client, selected, dbs


# ============================================================ last-operation banner

def render_last_op(name: str) -> None:
    op = st.session_state.get("last_op")
    if not op or op.get("db") != name:
        return
    kind = op["kind"]
    if kind in ("failure",):
        show_error(op)
    elif kind == "insert":
        r = op["report"]
        st.success(f"Inserted key {r['key']} → {r['value']}" if r["outcome"] == "INSERTED"
                   else f"Updated key {r['key']}: {r['previousValue']} → {r['value']}")
        with st.expander("What the engine did", expanded=True):
            for line in describe_insert(r):
                st.markdown(f"- {line}")
            st.caption(describe_transaction(r) + " See the WAL tab for the records.")
            if r["pagesAllocated"]:
                st.caption(f"Pages appended to the database file during this insert: {r['pagesAllocated']}")
    elif kind == "bulk":
        b = op["result"]
        st.success(f"Bulk insert ({b['order']}): {b['inserted']} new, {b['updated']} updated "
                   f"in {b['elapsedMillis']} ms")
        c = st.columns(5)
        c[0].metric("Leaf splits", b["leafSplits"])
        c[1].metric("Internal splits", b["internalSplits"])
        c[2].metric("New roots", b["newRootsCreated"])
        c[3].metric("Root before", b["rootPageIdBefore"])
        c[4].metric("Root after", b["rootPageIdAfter"])
    elif kind == "search":
        t = op["trace"]
        (st.success if t["found"] else st.info)(
            f"Key {t['key']} found with value {t['value']}" if t["found"] else f"Key {t['key']} not found")
        if not t["traceMatchesEngine"]:
            st.error("The traced descent and the engine's own search() disagree. This should never happen; "
                     "please report it.")
        for line in describe_search(t):
            st.markdown(f"- {line}")
    elif kind == "created":
        st.success(f"Created and opened '{name}' (fan-out {op['leaf']}/{op['internal']}).")
    elif kind == "opened":
        st.success(f"Opened '{name}'. The engine ran recovery over its WAL before reading the tree.")
    elif kind == "closed":
        st.info(f"Closed '{name}'. Its files are intact on disk.")


# ============================================================ tabs

def next_free_key(snap: dict) -> int:
    """One more than the largest key, read from the rightmost leaf of the engine's snapshot."""
    by_id = {n["pageId"]: n for n in snap["nodes"]}
    for leaf_id in reversed(snap["leafOrder"]):
        keys = by_id[leaf_id]["keys"]
        if keys:
            return min(keys[-1] + 1, INT_MAX)
    return 1


def tab_tree(client: ExplorerClient, name: str, db: dict) -> None:
    render_last_op(name)

    snap = fetch(client.tree, name, action="read the tree")
    if snap is None:
        return
    if st.session_state.get("ins_init_db") != name:  # first visit to this database in this session
        st.session_state["ins_key"] = next_free_key(snap)
        st.session_state["ins_val"] = max(INT_MIN, min(INT_MAX, st.session_state["ins_key"] * 10))
        st.session_state["srch_key"] = snap["nodes"][0]["keys"][0] if snap["keyCount"] and snap["nodes"][0]["keys"] else 1
        st.session_state["ins_init_db"] = name

    left, right = st.columns(2)
    with left:
        with st.form("insert_form", border=True):
            st.markdown("**Insert**")
            a, b = st.columns(2)
            a.number_input("Key", INT_MIN, INT_MAX, step=1, key="ins_key")
            b.number_input("Value", INT_MIN, INT_MAX, step=1, key="ins_val")
            st.form_submit_button("Insert / update", on_click=cb_insert, args=(name,), type="primary")
    with right:
        with st.form("search_form", border=True):
            st.markdown("**Search**")
            st.number_input("Key", INT_MIN, INT_MAX, step=1, key="srch_key")
            st.form_submit_button("Search", on_click=cb_search, args=(name,))

    with st.expander("Bulk insert: many real inserts at once"):
        c = st.columns(6)
        c[0].number_input("First key", INT_MIN, INT_MAX, 1, key="bulk_start")
        c[1].number_input("Count", 1, 2000, 40, key="bulk_count")
        c[2].number_input("Step", -1_000_000, 1_000_000, 1, key="bulk_step")
        c[3].selectbox("Order", ["ascending", "shuffled"], key="bulk_order")
        c[4].number_input("Value = key ×", -1000, 1000, 10, key="bulk_mult")
        c[5].number_input("Shuffle seed", 0, 2 ** 31 - 1, 42, key="bulk_seed")
        st.button("Run bulk insert", on_click=cb_bulk, args=(name,), key="btn_bulk")

    m = st.columns(6)
    m[0].metric("Height", snap["height"])
    m[1].metric("Keys", f"{snap['keyCount']:,}")
    m[2].metric("Leaf pages", snap["leafCount"])
    m[3].metric("Internal pages", snap["internalCount"])
    m[4].metric("Root page", snap["rootPageId"])
    m[5].metric("Pages in file", snap["totalPages"],
                help="Includes the metadata page (0) and any allocated page not reachable from the root.")
    ok, msg = structure_status(snap)
    (st.caption if ok else st.error)(("✓ Structure check by the engine's reader: " if ok else "Structure check failed: ") + msg)

    if snap["keyCount"] == 0:
        st.info("The tree is empty: one empty leaf page (the root). Insert a key to begin.")

    # --- highlight derived from the last operation on this database only
    op = st.session_state.get("last_op")
    path_pages = leaf_page = focus_key = found = focus_leaf = None
    badges: dict[int, str] = {}
    if op and op.get("db") == name:
        if op["kind"] == "search":
            t = op["trace"]
            path_pages = [s["pageId"] for s in t["path"]] + [t["leafPageId"]]
            leaf_page, focus_key, found = t["leafPageId"], t["key"], t["found"]
        elif op["kind"] == "insert":
            badges = changed_pages(op["report"])
            focus_leaf = op["report"]["leafPageId"]

    total = snap["leafCount"]
    c1, c2, c3 = st.columns([1, 1, 2])
    max_leaves = c1.slider("Leaves drawn", 2, 24, 8, key="max_leaves",
                           help="Wide trees are drawn as a window of consecutive leaves plus their ancestors.")
    start = None
    if total > max_leaves:
        follow = c2.checkbox("Follow last operation", value=True, key="follow_op")
        if not follow:
            start = c3.slider("First visible leaf", 1, total - max_leaves + 1, 1, key="win_start") - 1
    html, info = render_tree_html(snap, path_pages=path_pages, leaf_page=leaf_page, focus_key=focus_key,
                                  found=found, badges=badges, focus_leaf=focus_leaf, max_leaves=max_leaves,
                                  window_start=start)
    embed_html(html, info.iframe_height)
    st.caption("Page numbers are physical page IDs in the database file. Keys are shown in sorted order; leaf "
               "values are in the Pages tab. Dashed green arrows are the leaves' next-leaf pointers.")

    prev = st.session_state.get("prev_tree")
    if op and op.get("db") == name and op["kind"] in ("insert", "bulk") and prev and prev["db"] == name:
        with st.expander("Before / after this operation"):
            t_after, t_before = st.tabs(["After", "Before"])
            anchor = op["report"]["leafPageId"] if op["kind"] == "insert" else None
            with t_after:
                h2, i2 = render_tree_html(snap, badges=badges, focus_leaf=anchor, max_leaves=max_leaves,
                                          window_start=start)
                embed_html(h2, i2.iframe_height)
            with t_before:
                h3, i3 = render_tree_html(prev["tree"], focus_leaf=anchor, max_leaves=max_leaves)
                embed_html(h3, i3.iframe_height)
                st.caption("Snapshot taken from the engine immediately before the operation.")


def tab_pages(client: ExplorerClient, name: str) -> None:
    pl = fetch(client.pages, name, action="list pages")
    snap = fetch(client.tree, name, action="read the tree")
    if pl is None or snap is None:
        return
    depth = {n["pageId"]: n["depth"] for n in snap["nodes"]}
    reachable = sum(1 for p in pl["pages"] if p["reachable"])
    m = st.columns(5)
    m[0].metric("Page size", "4096 B")
    m[1].metric("Pages in file", pl["totalPages"])
    m[2].metric("Reachable from root", reachable)
    m[3].metric("Not reachable", pl["returned"] - reachable,
                help="Pages allocated in the file but not part of the current tree: e.g. pages appended by an "
                     "insert that did not complete. The engine does not reuse them.")
    m[4].metric("Root page", pl["rootPageId"])

    rows = [{"Page": p["pageId"], "Role": p["role"], "Root": "●" if p["root"] else "",
             "Tree depth": depth.get(p["pageId"], None), "Keys": p["numKeys"],
             "Reachable": p["reachable"], "File offset": p["pageId"] * 4096} for p in pl["pages"]]
    df = pd.DataFrame(rows)
    st.dataframe(df, hide_index=True, width="stretch", height=min(380, 36 + 35 * len(rows)))
    if pl["truncated"]:
        st.caption(f"Showing the first {pl['returned']} of {pl['totalPages']} pages.")

    options = [p["pageId"] for p in pl["pages"]]
    sel = st.selectbox("Inspect page", options, key="inspect_page",
                       format_func=lambda i: f"page {i}" + (" (metadata)" if i == 0 else ""))
    hexv = st.checkbox("Show first 96 bytes as hex (diagnostic)", key="show_hex")
    info = fetch(client.page, name, int(sel), bool(hexv), action=f"read page {sel}")
    if info is None:
        return
    c = st.columns(4)
    c[0].metric("Role", info["role"])
    c[1].metric("File offset", f"{info['byteOffset']:,}")
    c[2].metric("Keys", info["numKeys"])
    c[3].metric("Reachable", "yes" if info["reachable"] else "no")
    if info["note"]:
        st.caption(info["note"])
    if info["role"] == "METADATA":
        st.markdown(f"Magic number `{info['magicHex']}` ({'valid' if info['magicOk'] else 'INVALID'}) · "
                    f"root page ID stored here: **{info['metadataRootPageId']}**")
    elif info["role"] == "LEAF":
        st.markdown(f"Next leaf page: **{'none (last leaf)' if info['nextLeafPageId'] == -1 else info['nextLeafPageId']}**")
        st.dataframe(pd.DataFrame({"Slot": range(len(info["keys"])), "Key": info["keys"], "Value": info["values"]}),
                     hide_index=True, width="stretch", height=min(300, 36 + 35 * max(1, len(info["keys"]))))
    elif info["role"] == "INTERNAL":
        rows = []
        for i, child in enumerate(info["childPageIds"]):
            lo = "-∞" if i == 0 else info["keys"][i - 1]
            hi = "+∞" if i == len(info["keys"]) else info["keys"][i]
            rows.append({"Child slot": i, "Child page": child, "Keys in child: from (≥)": lo, "to (<)": hi})
        st.dataframe(pd.DataFrame(rows), hide_index=True, width="stretch",
                     height=min(300, 36 + 35 * max(1, len(rows))))
    if info.get("hexPreview"):
        st.code(info["hexPreview"], language=None)


def tab_wal(client: ExplorerClient, name: str) -> None:
    ss = st.session_state
    limit = st.selectbox("Records per page", [25, 50, 100, 250], index=1, key="wal_limit")
    offset = ss.get("wal_offset", 0)
    w = fetch(client.wal, name, offset, limit, action="read the write-ahead log")
    if w is None:
        return
    s = w["summary"]
    m = st.columns(5)
    m[0].metric("WAL size", f"{w['walBytes']:,} B")
    m[1].metric("Records", s["totalRecords"])
    m[2].metric("Page images", s["pageImageRecords"])
    m[3].metric("Commit records", s["commitRecords"])
    m[4].metric("Transactions", s["transactions"])
    if s["transactionsWithoutCommitRecord"]:
        st.warning(f"Transactions with page images but no commit record: "
                   f"{fmt_ids(s['transactionsWithoutCommitRecord'])}. Recovery ignores these.")
    else:
        st.caption("Every transaction in this log has a commit record.")
    if w["trailingBytes"]:
        st.warning(f"{w['trailingBytes']} bytes after the last complete record (an incomplete record that the "
                   f"engine trims when it next opens the log).")
    if s["scanTruncated"]:
        st.warning("The summary stopped scanning at the safety limit; counts are lower bounds.")

    rows = []
    for r in w["records"]:
        detail = ""
        if r["type"] == "PAGE_IMAGE":
            if r["imageNodeType"] == "METADATA":
                detail = f"metadata · root = {r['imageRootPageId']}"
            else:
                detail = f"{r['imageNodeType'].lower().replace('_', ' ')} · {r['imageNumKeys']} keys"
        rows.append({"#": r["index"], "Offset": r["offset"], "Bytes": r["sizeBytes"], "Type": r["type"],
                     "Txn": r["transactionId"], "Page": r["pageId"], "Page image holds": detail,
                     "Commit record in log": "yes" if r["commitRecordPresent"] else "NO"})
    st.dataframe(pd.DataFrame(rows), hide_index=True, width="stretch", height=min(460, 36 + 35 * max(1, len(rows))))
    c = st.columns([1, 1, 4])
    c[0].button("⏮ Start", on_click=cb_wal_offset, args=(0,), disabled=offset == 0, key="wal_start")
    c[1].button("Next ▶", on_click=cb_wal_offset, args=(w["nextOffset"],), disabled=w["nextOffset"] < 0,
                key="wal_next")
    st.caption("Records are listed in log order. Each insert is one transaction: its page images, then a commit "
               "record. “Commit record in log” means a COMMIT record for that transaction ID is present, which "
               "is the rule recovery uses to decide what to replay. Page images are 4 KB each and are summarised, "
               "not dumped.")


def tab_recovery(client: ExplorerClient) -> None:
    st.markdown("Controlled recovery demonstrations on a **temporary database that is discarded afterwards**. "
                "Your databases are never touched.")
    scenarios = fetch(client.recovery_scenarios, action="list recovery scenarios")
    if not scenarios:
        return
    titles = {s["id"]: s["title"] for s in scenarios}
    sid = st.selectbox("Scenario", list(titles), format_func=lambda i: titles[i], key="rec_scenario")
    sc = next(s for s in scenarios if s["id"] == sid)
    st.markdown(sc["summary"])
    st.caption("Simulated step: " + sc["crashStep"])
    st.button("Run scenario", on_click=cb_run_recovery, args=(sid,), key="btn_recovery", type="primary")

    rr = st.session_state.get("recovery_result")
    if not rr or rr["scenario"] != sid:
        return
    if rr["error"]:
        show_error(rr["error"])
        return
    r = rr["result"]
    with st.expander("Exactly what this simulation does and does not do"):
        st.markdown("**Does**")
        for line in r["simulation"]["whatItDoes"]:
            st.markdown(f"- {line}")
        st.markdown("**Does not**")
        for line in r["simulation"]["whatItDoesNot"]:
            st.markdown(f"- {line}")

    before, rec, after, out = r["beforeRecovery"], r["recovery"], r["afterRecovery"], r["outcome"]
    c1, c2, c3 = st.columns(3)
    with c1:
        st.markdown("**1 · Files before recovery**")
        ws = before["wal"]["summary"]
        st.markdown(f"- WAL: {ws['totalRecords']} records, {ws['transactions']} transactions")
        miss = ws["transactionsWithoutCommitRecord"]
        st.markdown(f"- Transactions without a commit record: **{fmt_ids(miss) if miss else 'none'}**")
        if before["walBytesDiscardedByWalOpen"]:
            st.markdown(f"- Incomplete record: **{before['walBytesDiscardedByWalOpen']} bytes** trimmed when the "
                        f"WAL is opened")
        v = before["targetKeyValueInDatabaseFile"]
        st.markdown(f"- Key {r['baseline']['targetKey']} in the database file alone: "
                    f"**{'absent' if v is None else v}**")
    with c2:
        st.markdown("**2 · Recovery (engine's `initialize()`)**")
        st.markdown(f"- Pages rewritten: **{fmt_ids(rec['pagesChangedByRecovery']) or 'none'}**")
        st.markdown(f"- Pages appended: **{fmt_ids(rec['pagesAppendedByRecovery']) or 'none'}**")
        st.markdown(f"- Database pages: {rec['databasePagesBefore']} → {rec['databasePagesAfter']}")
        st.markdown(f"- Replaying again changes nothing: **{'yes' if rec['secondOpenChangedNothing'] else 'NO'}**")
        st.caption("Measured by comparing page bytes before and after opening.")
    with c3:
        st.markdown("**3 · After recovery**")
        v = after["targetKeyValue"]
        st.markdown(f"- Key {r['baseline']['targetKey']} after opening: **{'absent' if v is None else v}**")
        st.markdown(f"- Tree: height {after['databaseTree']['height']}, {after['databaseTree']['keyCount']} keys, "
                    f"root page {after['databaseTree']['rootPageId']}")
        st.markdown(f"- Structure check: {'ok' if not after['databaseTree']['invariantViolations'] else 'FAILED'}")
    present = "present" if out["observedTargetKeyPresent"] else "absent"
    expect = "present" if out["expectedTargetKeyPresent"] else "absent"
    (st.success if out["matchesExpectation"] else st.error)(
        f"Key {r['baseline']['targetKey']} is {present} after recovery; the recovery rule predicts {expect}. "
        + ("Matches." if out["matchesExpectation"] else "Does not match, which indicates a bug."))

    with st.expander("Trees: database file before recovery / after recovery"):
        tb, ta = st.tabs(["Before recovery", "After recovery"])
        with tb:
            h, i = render_tree_html(before["databaseTree"], max_leaves=8)
            embed_html(h, i.iframe_height)
        with ta:
            h, i = render_tree_html(after["databaseTree"], max_leaves=8)
            embed_html(h, i.iframe_height)
    with st.expander("WAL records at the moment of the simulated crash"):
        rows = [{"#": x["index"], "Type": x["type"], "Txn": x["transactionId"], "Page": x["pageId"],
                 "Commit record in log": "yes" if x["commitRecordPresent"] else "NO"}
                for x in before["wal"]["records"][-12:]]
        st.dataframe(pd.DataFrame(rows), hide_index=True, width="stretch")
        st.caption("The last 12 records.")
    st.caption(rec["rule"])


# ============================================================ main

def main() -> None:
    init_state()
    st.title("Storage Engine Explorer")
    side = sidebar()
    if side is None:
        st.error("**The Java backend is not reachable.**")
        st.markdown("Start it from the project root, then reload this page:\n\n"
                    "```\nvisualizer/start-backend.sh        # macOS / Linux\n"
                    "visualizer\\start-backend.ps1       # Windows PowerShell\n```\n"
                    "The backend is the real storage engine; this page cannot show anything without it. "
                    "If it runs on another port, change *Backend URL* in the sidebar.")
        st.stop()
    client, selected, dbs = side

    tab_recovery_only = st.tabs(["B+ tree", "Pages", "Write-ahead log", "Recovery demo"])
    t_tree, t_pages, t_wal, t_rec = tab_recovery_only

    with t_rec:
        tab_recovery(client)

    if selected is None:
        for t in (t_tree, t_pages, t_wal):
            with t:
                st.info("Create a database in the sidebar to begin. Everything on these tabs is read from the "
                        "Java engine.")
        return
    name = selected["name"]
    if selected["state"] != "OPEN":
        for t in (t_tree, t_pages, t_wal):
            with t:
                render_last_op(name)
                st.warning(f"Database '{name}' exists on disk but is **closed**. Opening it runs the engine's "
                           f"recovery over its write-ahead log first.")
        return
    with t_tree:
        tab_tree(client, name, selected)
    with t_pages:
        tab_pages(client, name)
    with t_wal:
        tab_wal(client, name)


main()
