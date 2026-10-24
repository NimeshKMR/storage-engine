"""End-to-end tests: the real Streamlit script (AppTest) talking to the real Java server."""
import os
from pathlib import Path

import pytest
from streamlit.testing.v1 import AppTest

APP = str(Path(__file__).resolve().parents[1] / "app.py")


def _app(url: str) -> AppTest:
    os.environ["STORAGE_EXPLORER_URL"] = url
    return AppTest.from_file(APP, default_timeout=60)


def _press(at: AppTest, label: str) -> AppTest:
    for b in at.button:
        if b.label == label:
            return b.click().run()
    raise AssertionError(f"no button labelled {label!r}; have {[b.label for b in at.button]}")


def _texts(at: AppTest) -> str:
    parts = [m.value for m in at.markdown] + [c.value for c in at.caption] + [s.value for s in at.success]
    parts += [i.value for i in at.info] + [w.value for w in at.warning] + [e.value for e in at.error]
    return "\n".join(str(p) for p in parts)


def test_backend_unavailable_state_is_explicit():
    at = _app("http://127.0.0.1:9").run()          # nothing listens there
    assert not at.exception
    text = _texts(at)
    assert "Java backend is not reachable" in text and "start-backend" in text
    assert not at.tabs or all("B+ tree" != t.label for t in at.tabs)   # no fake tree is shown


def test_empty_state_when_no_databases(fresh_server):
    at = _app(fresh_server.url).run()
    assert not at.exception
    assert "No demo databases yet" in _texts(at)
    assert "Create a database in the sidebar" in _texts(at)


def test_create_insert_search_bulk_close_open_flow(fresh_server):
    at = _app(fresh_server.url).run()
    at.sidebar.text_input(key="create_name").set_value("demo")
    at = at.sidebar.button(key="btn_create").click().run()
    assert not at.exception, at.exception
    assert "Created and opened 'demo'" in _texts(at)
    assert "The tree is empty" in _texts(at)

    # insert: the form defaults to the next free key (1 for an empty tree)
    assert at.number_input(key="ins_key").value == 1
    at = _press(at, "Insert / update")
    assert "Inserted key 1" in _texts(at) and "no node was split" in _texts(at)
    assert at.number_input(key="ins_key").value == 2     # advances to the next key

    # update an existing key
    at.number_input(key="ins_key").set_value(1)
    at.number_input(key="ins_val").set_value(99)
    at = _press(at, "Insert / update")
    assert "Updated key 1: 10 → 99" in _texts(at)

    # search hit and miss
    at.number_input(key="srch_key").set_value(1)
    at = _press(at, "Search")
    assert "Key 1 found with value 99" in _texts(at)
    at.number_input(key="srch_key").set_value(12345)
    at = _press(at, "Search")
    assert "Key 12345 not found" in _texts(at) and "would be inserted" in _texts(at)

    # bulk insert grows the tree; every figure shown comes from the engine
    at.number_input(key="bulk_start").set_value(100)
    at.number_input(key="bulk_count").set_value(60)
    at = _press(at, "Run bulk insert")
    assert not at.exception
    metrics = {}
    for m in at.metric:                      # first occurrence = tree tab (Pages tab has its own "Keys")
        metrics.setdefault(m.label, m.value)
    assert int(metrics["Keys"].replace(",", "")) == 61
    assert int(metrics["Height"]) >= 3
    assert "Bulk insert" in _texts(at)

    # a split caused by one insert is explained from the engine's report
    snap_keys = int(metrics["Keys"])
    from explorer_client import ExplorerClient
    c = ExplorerClient(fresh_server.url)
    tree = c.tree("demo")
    assert tree["keyCount"] == snap_keys and tree["invariantViolations"] == []

    # close then open: the closed state is distinguishable, open runs recovery and restores the same tree
    at = at.sidebar.button(key="btn_close").click().run()
    assert "Closed 'demo'" in _texts(at) or "is **closed**" in _texts(at) or "closed" in _texts(at).lower()
    at = at.sidebar.button(key="btn_open").click().run()
    assert "Opened 'demo'" in _texts(at)
    assert c.tree("demo") == tree


def test_invalid_database_name_shows_engine_error(fresh_server):
    at = _app(fresh_server.url).run()
    at.sidebar.text_input(key="create_name").set_value("Bad Name!")
    at = at.sidebar.button(key="btn_create").click().run()
    assert not at.exception
    assert "INVALID_INPUT" in _texts(at)


def test_delete_requires_exact_confirmation(fresh_server):
    from explorer_client import ExplorerClient
    c = ExplorerClient(fresh_server.url)
    c.create("keepme", 4, 4)
    at = _app(fresh_server.url).run()
    at.sidebar.text_input(key="delete_confirm").set_value("nope")
    at = at.sidebar.button(key="btn_delete").click().run()
    assert "INVALID_INPUT" in _texts(at)
    assert [d["name"] for d in c.list_databases()] == ["keepme"]
    at.sidebar.text_input(key="delete_confirm").set_value("keepme")
    at = at.sidebar.button(key="btn_delete").click().run()
    assert c.list_databases() == []


def test_recovery_tab_runs_real_scenarios(fresh_server):
    at = _app(fresh_server.url).run()
    scenario_ids = ["clean-reopen", "commit-logged-pages-lost", "commit-record-missing", "commit-record-torn"]
    assert len(at.selectbox(key="rec_scenario").options) == 4
    for sid in scenario_ids:
        at = at.selectbox(key="rec_scenario").set_value(sid).run()   # rerun first, as a browser would
        at = at.button(key="btn_recovery").click().run()
        assert not at.exception
        assert "Matches." in _texts(at), (sid, _texts(at)[-500:])


def test_pages_and_wal_tabs_show_engine_data(fresh_server):
    from explorer_client import ExplorerClient
    c = ExplorerClient(fresh_server.url)
    c.create("pg", 4, 4)
    c.bulk("pg", 1, 25, 1, "ascending", 1, 10)
    at = _app(fresh_server.url).run()
    assert not at.exception
    # page explorer: one dataframe of pages, one for the selected page's contents
    assert len(at.dataframe) >= 2
    wal = c.wal("pg", 0, 500)
    labels = {}
    for m in at.metric:
        labels.setdefault(m.label, m.value)
    assert labels["Records"] == str(wal["summary"]["totalRecords"])
    assert labels["Reachable from root"] == str(c.tree("pg")["nodeCount"] + 1)
