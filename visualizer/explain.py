"""Turns engine reports into plain-language lines.

Every sentence is built from fields the Java engine reported (InsertReport, SearchTrace,
TreeSnapshot). Nothing is inferred from the inserted key.
"""
from __future__ import annotations

from typing import Any


def describe_insert(report: dict) -> list[str]:
    """Human-readable change summary for one InsertReport."""
    key, value = report["key"], report["value"]
    lines: list[str] = []
    if report["outcome"] == "UPDATED":
        lines.append(f"Key {key} already existed in leaf page {report['leafPageId']}; "
                     f"its value changed from {report['previousValue']} to {value}. No structure change.")
    else:
        lines.append(f"Key {key} was inserted into leaf page {report['leafPageId']} with value {value}.")

    splits = report.get("splits", [])
    if not splits:
        if report["outcome"] == "INSERTED":
            lines.append("The leaf had room, so no node was split.")
    for s in splits:
        kind = "leaf" if s["kind"] == "LEAF" else "internal node"
        lines.append(
            f"Split {kind} page {s['leftPageId']}: the upper half moved to new page {s['newRightPageId']}; "
            f"separator key {s['separatorKey']} was passed up.")
        if s["createdNewRoot"]:
            lines.append(f"Page {s['leftPageId']} was the root, so new root page {s['parentPageId']} was created "
                         f"with children {s['leftPageId']} and {s['newRightPageId']}.")
        else:
            lines.append(f"Parent page {s['parentPageId']} received separator {s['separatorKey']} "
                         f"and a pointer to page {s['newRightPageId']}.")
    if report["previousRootPageId"] != report["rootPageId"]:
        lines.append(f"Root page changed from {report['previousRootPageId']} to {report['rootPageId']} "
                     f"(the metadata page 0 now records the new root).")
    return lines


def describe_transaction(report: dict) -> str:
    if report["transactionId"] < 0:
        return "No write-ahead log is attached to this tree."
    text = (f"Transaction {report['transactionId']} logged {len(report['pagesWritten'])} page image(s) "
            f"{report['pagesWritten']} and ended in state {report['transactionState']}.")
    if report["transactionState"] == "APPLIED":
        text += (" For this state the engine had asked the OS to force the log to storage before writing the "
                 "commit record, again after it, and finally the database file (FileChannel.force); whether the "
                 "hardware honours that is outside the engine's control.")
    return text


def changed_pages(report: dict) -> dict[int, str]:
    """Page id -> badge text for pages this insert touched, using only reported fields."""
    out: dict[int, str] = {}
    for p in report.get("pagesWritten", []):
        out[p] = "WRITTEN"
    out[report["leafPageId"]] = "UPDATED" if report["outcome"] == "UPDATED" else "INSERT"
    for s in report.get("splits", []):
        out[s["leftPageId"]] = "SPLIT"
        out[s["newRightPageId"]] = "NEW"
        if s["createdNewRoot"]:
            out[s["parentPageId"]] = "NEW ROOT"
    return out


def describe_search(trace: dict) -> list[str]:
    key = trace["key"]
    lines: list[str] = []
    hops = []
    for step in trace["path"]:
        lo = "-inf" if step["lowerBound"] is None else step["lowerBound"]
        hi = "+inf" if step["upperBound"] is None else step["upperBound"]
        hops.append(f"page {step['pageId']} (child {step['childIndex']}, range [{lo}, {hi}))")
    hops.append(f"leaf page {trace['leafPageId']}")
    lines.append("Path: " + " → ".join(hops))
    if trace["found"]:
        lines.append(f"Key {key} is at slot {trace['foundIndex']} of {trace['leafKeyCount']} in the leaf; "
                     f"value {trace['value']}.")
    else:
        before, after = trace["keyBefore"], trace["keyAfter"]
        if before is None and after is None:
            where = "the leaf is empty"
        elif before is None:
            where = f"it would be the first key in that leaf, before {after}"
        elif after is None:
            where = f"it would be the last key in that leaf, after {before}"
        else:
            where = f"between {before} and {after} in that leaf"
        lines.append(f"Key {key} is not in the tree. It would be inserted at slot {trace['insertionIndex']} of leaf "
                     f"page {trace['leafPageId']} ({where}).")
        if trace["leafAtCapacity"]:
            lines.append(f"That leaf already holds {trace['leafCapacity']} keys (its capacity), "
                         f"so inserting would split it.")
    return lines


def structure_status(snapshot: dict) -> tuple[bool, str]:
    problems = snapshot.get("invariantViolations", [])
    if problems:
        return False, f"{len(problems)} problem(s): " + "; ".join(problems[:3])
    return True, "Keys ordered, separator ranges respected, leaf chain matches tree order."


def fmt_ids(ids: list[Any], limit: int = 8) -> str:
    if len(ids) <= limit:
        return ", ".join(str(i) for i in ids)
    return ", ".join(str(i) for i in ids[:limit]) + f", … (+{len(ids) - limit})"
