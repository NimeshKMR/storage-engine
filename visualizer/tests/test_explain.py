from explain import (changed_pages, describe_insert, describe_search, describe_transaction, fmt_ids,
                     structure_status)


def test_update_and_plain_insert_descriptions(client, db_name):
    first = client.insert(db_name, 1, 10)
    lines = describe_insert(first)
    assert "inserted into leaf page" in lines[0] and "no node was split" in lines[1]
    upd = client.insert(db_name, 1, 11)
    assert "from 10 to 11" in describe_insert(upd)[0]


def test_leaf_split_description_matches_report(client, db_name):
    for k in range(1, 5):
        client.insert(db_name, k, k)
    rep = client.insert(db_name, 5, 5)           # fills the 4-slot leaf, forcing a split
    text = "\n".join(describe_insert(rep))
    s = rep["splits"][0]
    assert f"separator key {s['separatorKey']}" in text
    assert f"new page {s['newRightPageId']}" in text
    assert f"new root page {rep['rootPageId']}" in text
    badges = changed_pages(rep)
    assert badges[s["newRightPageId"]] == "NEW" and badges[s["parentPageId"]] == "NEW ROOT"
    assert badges[s["leftPageId"]] == "SPLIT"


def test_cascade_descriptions_only_use_reported_splits(client, db_name):
    cascade = None
    for k in range(1, 100):
        rep = client.insert(db_name, k, k)
        if len(rep["splits"]) >= 2:
            cascade = rep
            break
    assert cascade is not None
    text = "\n".join(describe_insert(cascade))
    assert text.count("Split ") == len(cascade["splits"])


def test_search_descriptions(client, db_name):
    client.bulk(db_name, 10, 30, 10, "ascending", 1, 1)
    hit = describe_search(client.search(db_name, 100))
    assert "Path:" in hit[0] and "is at slot" in hit[1]
    miss = describe_search(client.search(db_name, 105))
    assert "not in the tree" in miss[1] and "after 100" in miss[1] and "last key in that leaf" in miss[1]
    client.insert(db_name, 3, 3)
    mid = describe_search(client.search(db_name, 4))
    assert "after 3" in mid[1] or "between 3 and" in mid[1]


def test_transaction_and_structure_text(client, db_name):
    rep = client.insert(db_name, 1, 1)
    assert "Transaction" in describe_transaction(rep) and "APPLIED" in describe_transaction(rep)
    ok, msg = structure_status(client.tree(db_name))
    assert ok and "ordered" in msg
    bad, msg2 = structure_status({"invariantViolations": ["a", "b"]})
    assert not bad and "2 problem" in msg2


def test_fmt_ids():
    assert fmt_ids([1, 2, 3]) == "1, 2, 3"
    assert fmt_ids(list(range(12))).endswith("(+4)")
