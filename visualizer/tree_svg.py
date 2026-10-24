"""Renders a TreeSnapshot (as returned by the Java engine) as a standalone HTML/SVG block.

The renderer is a pure function of the snapshot: it draws exactly the nodes, keys, child pointers and
next-leaf pointers it is given. Large trees are shown as a window of consecutive leaves together with
their ancestors, and the result says how much was left out.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from html import escape

CELL_W = 34
CELL_H = 28
PAD = 8
HEADER_H = 20
NODE_H = HEADER_H + CELL_H + PAD
H_GAP = 26
LEVEL_GAP = 96
MARGIN_X = 24
MARGIN_TOP = 26
MARGIN_BOTTOM = 40
MAX_CELLS = 8          # cells drawn before a node's keys are abbreviated
GHOST_SLOTS_MAX = 8    # draw empty slots up to capacity when the capacity is at most this

COLORS = {
    "internal_fill": "#e8eefc", "internal_stroke": "#5b7bd5", "internal_head": "#d3defa",
    "leaf_fill": "#e9f5ec", "leaf_stroke": "#4f9a67", "leaf_head": "#d2ebd9",
    "ghost": "#aab2bf", "text": "#1c2430", "muted": "#667085",
    "path": "#e08a00", "path_fill": "#fff3d6", "hit": "#e08a00", "edge": "#8a93a3",
    "chain": "#4f9a67", "new": "#c2410c", "root": "#1c2430",
}


@dataclass
class RenderInfo:
    total_leaves: int
    shown_leaves: int
    window_start: int
    window_end: int       # exclusive
    truncated: bool
    nodes_drawn: int
    width: int
    height: int          # height of the SVG itself
    iframe_height: int = 0  # height to give the embedding iframe


@dataclass
class _Box:
    pid: int
    node: dict
    width: int
    x: float = 0.0        # left edge
    y: float = 0.0
    kids: list[int] = field(default_factory=list)
    hidden_kids: int = 0
    subtree_w: float = 0.0


def _cells_for(node: dict, capacity: int) -> tuple[list[str], int, bool]:
    """Returns (labels for drawn key cells, number of ghost slots, abbreviated)."""
    keys = node["keys"]
    n = len(keys)
    if n > MAX_CELLS:
        labels = [str(k) for k in keys[:4]] + [f"+{n - 7}"] + [str(k) for k in keys[-3:]]
        return labels, 0, True
    ghosts = max(0, capacity - n) if capacity <= GHOST_SLOTS_MAX else 0
    return [str(k) for k in keys], ghosts, False


def render_tree_html(snapshot: dict, *, path_pages: list[int] | None = None, leaf_page: int | None = None,
                     focus_key: int | None = None, found: bool | None = None,
                     badges: dict[int, str] | None = None, focus_leaf: int | None = None,
                     max_leaves: int = 8, window_start: int | None = None,
                     title: str | None = None) -> tuple[str, RenderInfo]:
    nodes = {n["pageId"]: n for n in snapshot["nodes"]}
    root = snapshot["rootPageId"]
    leaf_order: list[int] = snapshot["leafOrder"]
    total_leaves = len(leaf_order)
    path_set = set(path_pages or [])
    badges = badges or {}

    # ---- choose the visible window of leaves
    if total_leaves <= max_leaves:
        lo, hi = 0, total_leaves
    else:
        if window_start is None:
            anchor = focus_leaf if focus_leaf in leaf_order else (leaf_page if leaf_page in leaf_order else None)
            idx = leaf_order.index(anchor) if anchor is not None else 0
            window_start = idx - max_leaves // 2
        lo = max(0, min(window_start, total_leaves - max_leaves))
        hi = lo + max_leaves
    visible_leaves = set(leaf_order[lo:hi])

    # ---- which nodes to draw: visible leaves and all their ancestors
    inc_cache: dict[int, bool] = {}

    def inc(pid: int) -> bool:
        if pid not in inc_cache:
            n = nodes[pid]
            if n["type"] == "LEAF":
                inc_cache[pid] = pid in visible_leaves
            else:
                inc_cache[pid] = any(inc(c) for c in n["childPageIds"])
        return inc_cache[pid]

    boxes: dict[int, _Box] = {}

    def build(pid: int) -> None:
        n = nodes[pid]
        cap = snapshot["leafCapacity"] if n["type"] == "LEAF" else snapshot["internalCapacity"]
        labels, ghosts, _ = _cells_for(n, cap)
        cells = max(1, len(labels) + ghosts)
        box = _Box(pid=pid, node=n, width=cells * CELL_W + 2 * PAD)
        boxes[pid] = box
        if n["type"] == "INTERNAL":
            for c in n["childPageIds"]:
                if inc(c):
                    box.kids.append(c)
                    build(c)
            box.hidden_kids = len(n["childPageIds"]) - len(box.kids)

    if not inc(root):  # empty window cannot happen (a leaf always exists), but stay defensive
        raise ValueError("Snapshot has no drawable leaves")
    build(root)

    # ---- layout: subtree widths bottom-up, then positions top-down
    def measure(pid: int) -> float:
        b = boxes[pid]
        if not b.kids:
            b.subtree_w = b.width
        else:
            kids_w = sum(measure(c) for c in b.kids) + H_GAP * (len(b.kids) - 1)
            b.subtree_w = max(b.width, kids_w)
        return b.subtree_w

    measure(root)

    def place(pid: int, left: float) -> None:
        b = boxes[pid]
        b.y = MARGIN_TOP + nodes[pid]["depth"] * LEVEL_GAP
        if not b.kids:
            b.x = left + (b.subtree_w - b.width) / 2
            return
        kids_w = sum(boxes[c].subtree_w for c in b.kids) + H_GAP * (len(b.kids) - 1)
        cursor = left + (b.subtree_w - kids_w) / 2
        for c in b.kids:
            place(c, cursor)
            cursor += boxes[c].subtree_w + H_GAP
        first, last = boxes[b.kids[0]], boxes[b.kids[-1]]
        center = (first.x + first.width / 2 + last.x + last.width / 2) / 2
        b.x = center - b.width / 2

    place(root, MARGIN_X)

    max_depth = max(nodes[p]["depth"] for p in boxes)
    width = int(boxes[root].subtree_w + 2 * MARGIN_X)
    # nodes can sit slightly outside the subtree box when centred over uneven children; fit them all
    min_x = min(b.x for b in boxes.values())
    max_x = max(b.x + b.width for b in boxes.values())
    shift = MARGIN_X - min_x if min_x < MARGIN_X else 0
    if shift:
        for b in boxes.values():
            b.x += shift
        max_x += shift
    width = int(max(width, max_x + MARGIN_X))
    height = int(MARGIN_TOP + (max_depth + 1) * LEVEL_GAP + MARGIN_BOTTOM)

    out: list[str] = []
    c = COLORS

    # ---- edges (drawn first so nodes sit on top)
    for pid, b in boxes.items():
        n = b.node
        if n["type"] != "INTERNAL":
            continue
        children = n["childPageIds"]
        nk = len(n["keys"])
        for i, child in enumerate(children):
            if child not in boxes:
                continue
            if nk + 1 <= MAX_CELLS + 1 and nk <= MAX_CELLS:
                px = b.x + PAD + i * CELL_W
            else:
                px = b.x + PAD + (b.width - 2 * PAD) * i / max(1, nk)
            py = b.y + NODE_H
            cb = boxes[child]
            cx, cy = cb.x + cb.width / 2, cb.y
            on_path = pid in path_set and child in path_set
            col = c["path"] if on_path else c["edge"]
            sw = 2.6 if on_path else 1.3
            mid = (py + cy) / 2
            out.append(f'<path d="M{px:.1f},{py:.1f} C{px:.1f},{mid:.1f} {cx:.1f},{mid:.1f} {cx:.1f},{cy:.1f}" '
                       f'fill="none" stroke="{col}" stroke-width="{sw}"/>')

    # ---- next-leaf pointers
    shown_leaf_ids = [pid for pid in leaf_order[lo:hi]]
    for pid in shown_leaf_ids:
        b = boxes[pid]
        nxt = b.node["nextLeafPageId"]
        y = b.y + HEADER_H + CELL_H / 2 + 2
        x1 = b.x + b.width
        if nxt in boxes:
            x2 = boxes[nxt].x
            out.append(f'<path d="M{x1:.1f},{y:.1f} L{x2 - 3:.1f},{y:.1f}" stroke="{c["chain"]}" stroke-width="1.6" '
                       f'stroke-dasharray="4 3" fill="none" marker-end="url(#arrow)"/>')
        elif nxt == -1:
            out.append(f'<text x="{x1 + 6:.1f}" y="{y + 4:.1f}" font-size="11" fill="{c["muted"]}">∅</text>')
        else:
            out.append(f'<text x="{x1 + 6:.1f}" y="{y + 4:.1f}" font-size="10" fill="{c["muted"]}" '
                       f'font-family="monospace">→{nxt}</text>')

    # ---- nodes
    focus_pid = leaf_page
    if focus_pid is None:
        for pid_, label_ in badges.items():
            if label_ in ("NEW", "SPLIT", "INSERT", "UPDATED") and pid_ in boxes:
                focus_pid = pid_
                break
    for pid, b in boxes.items():
        n = b.node
        is_leaf = n["type"] == "LEAF"
        cap = snapshot["leafCapacity"] if is_leaf else snapshot["internalCapacity"]
        fill = c["leaf_fill"] if is_leaf else c["internal_fill"]
        stroke = c["leaf_stroke"] if is_leaf else c["internal_stroke"]
        head = c["leaf_head"] if is_leaf else c["internal_head"]
        on_path = pid in path_set
        is_hit_leaf = leaf_page == pid
        if on_path or is_hit_leaf:
            fill = c["path_fill"]
            stroke = c["path"]
        sw = 3 if (on_path or is_hit_leaf) else (2.4 if n["root"] else 1.5)
        x, y, w = b.x, b.y, b.width
        classes = "node" + (" root" if n["root"] else "") + (" focus" if pid == focus_pid else "")
        out.append(f'<g class="{classes}" data-page="{pid}">')
        out.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w}" height="{NODE_H}" rx="6" fill="{fill}" '
                   f'stroke="{stroke}" stroke-width="{sw}"/>')
        out.append(f'<rect x="{x + 1:.1f}" y="{y + 1:.1f}" width="{w - 2}" height="{HEADER_H - 1}" rx="5" fill="{head}" '
                   f'opacity="0.9"/>')
        label = ("LEAF" if is_leaf else "INTERNAL") + f" · page {pid}"
        out.append(f'<text x="{x + PAD:.1f}" y="{y + 14:.1f}" font-size="11" font-weight="600" fill="{c["text"]}" '
                   f'font-family="ui-monospace,Menlo,Consolas,monospace">{escape(label)}</text>')
        if n["root"]:
            out.append(f'<rect x="{x + w - 44:.1f}" y="{y - 18:.1f}" width="42" height="15" rx="7" fill="{c["root"]}"/>'
                       f'<text x="{x + w - 23:.1f}" y="{y - 7:.1f}" font-size="10" font-weight="700" fill="#fff" '
                       f'text-anchor="middle" font-family="sans-serif">ROOT</text>')
        if pid in badges:
            bw = 8 + 6.4 * len(badges[pid])
            out.append(f'<rect x="{x:.1f}" y="{y - 18:.1f}" width="{bw:.1f}" height="15" rx="7" fill="{c["new"]}"/>'
                       f'<text x="{x + bw / 2:.1f}" y="{y - 7:.1f}" font-size="10" font-weight="700" fill="#fff" '
                       f'text-anchor="middle" font-family="sans-serif">{escape(badges[pid])}</text>')

        labels, ghosts, abbreviated = _cells_for(n, cap)
        cy = y + HEADER_H
        for i, text in enumerate(labels):
            cx = x + PAD + i * CELL_W
            hit = (focus_key is not None and found and is_hit_leaf and not abbreviated
                   and i == _slot_of(n, focus_key))
            is_more = abbreviated and text.startswith("+") and i == 4
            cell_fill = c["hit"] if hit else ("none" if is_more else "#ffffff")
            dash = 'stroke-dasharray="3 2"' if is_more else ""
            out.append(f'<rect x="{cx:.1f}" y="{cy + 2:.1f}" width="{CELL_W}" height="{CELL_H - 4}" '
                       f'fill="{cell_fill}" stroke="{stroke}" stroke-width="1" {dash}/>')
            out.append(f'<text x="{cx + CELL_W / 2:.1f}" y="{cy + CELL_H / 2 + 5:.1f}" font-size="12" '
                       f'text-anchor="middle" fill="{"#ffffff" if hit else c["text"]}" '
                       f'font-family="ui-monospace,Menlo,Consolas,monospace">{escape(text)}</text>')
        for g in range(ghosts):
            cx = x + PAD + (len(labels) + g) * CELL_W
            out.append(f'<rect x="{cx:.1f}" y="{cy + 2:.1f}" width="{CELL_W}" height="{CELL_H - 4}" fill="none" '
                       f'stroke="{c["ghost"]}" stroke-width="1" stroke-dasharray="2 3"/>')
        if not labels and ghosts == 0:
            out.append(f'<text x="{x + w / 2:.1f}" y="{cy + CELL_H / 2 + 5:.1f}" font-size="11" text-anchor="middle" '
                       f'fill="{c["muted"]}" font-family="sans-serif">empty</text>')
        elif not labels:
            pass
        # insertion caret for a missing key
        if focus_key is not None and found is False and is_hit_leaf and not abbreviated:
            slot = _insertion_slot(n, focus_key)
            cx = x + PAD + slot * CELL_W
            out.append(f'<path d="M{cx:.1f},{cy - 1:.1f} L{cx:.1f},{cy + CELL_H + 4:.1f}" stroke="{c["hit"]}" '
                       f'stroke-width="3"/><path d="M{cx - 5:.1f},{cy + CELL_H + 10:.1f} L{cx:.1f},{cy + CELL_H + 3:.1f} '
                       f'L{cx + 5:.1f},{cy + CELL_H + 10:.1f} Z" fill="{c["hit"]}"/>')
        foot = []
        if b.hidden_kids:
            foot.append(f"{b.hidden_kids} child(ren) not drawn")
        if abbreviated:
            foot.append(f"{len(n['keys'])} keys")
        if foot:
            out.append(f'<text x="{x + w / 2:.1f}" y="{y + NODE_H + 13:.1f}" font-size="10" text-anchor="middle" '
                       f'fill="{c["muted"]}" font-family="sans-serif">{escape(" · ".join(foot))}</text>')
        out.append('</g>')

    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
           f'viewBox="0 0 {width} {height}" role="img" aria-label="B+ tree">'
           f'<defs><marker id="arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" '
           f'orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="{c["chain"]}"/></marker></defs>'
           + "".join(out) + "</svg>")

    truncated = total_leaves > (hi - lo)
    info = RenderInfo(total_leaves=total_leaves, shown_leaves=hi - lo, window_start=lo, window_end=hi,
                      truncated=truncated, nodes_drawn=len(boxes), width=width, height=height)
    note = ""
    if truncated:
        note = (f'<div class="note">Showing leaves {lo + 1}–{hi} of {total_leaves} '
                f'(leaf order, left to right) and their ancestors. Other subtrees are not drawn.</div>')
    legend = (
        '<div class="legend">'
        f'<span><i style="background:{c["internal_fill"]};border-color:{c["internal_stroke"]}"></i>internal node</span>'
        f'<span><i style="background:{c["leaf_fill"]};border-color:{c["leaf_stroke"]}"></i>leaf</span>'
        f'<span><i style="background:{c["path_fill"]};border-color:{c["path"]}"></i>search path / chosen leaf</span>'
        f'<span><i class="dash" style="border-color:{c["chain"]}"></i>next-leaf pointer</span>'
        '<span><i class="ghost"></i>free slot</span></div>')
    heading = f'<div class="title">{escape(title)}</div>' if title else ""
    html = (
        '<!doctype html><html><head><meta charset="utf-8"><style>'
        'body{margin:0;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:#1c2430;background:#ffffff}'
        '.wrap{border:1px solid #d9dee7;border-radius:8px;background:#fff;overflow:hidden}'
        '.title{padding:8px 12px 0;font-weight:600;font-size:13px}'
        '.legend{display:flex;gap:16px;flex-wrap:wrap;padding:8px 12px;font-size:12px;color:#556070;'
        'border-bottom:1px solid #eef0f4}'
        '.legend i{display:inline-block;width:12px;height:12px;border:1.5px solid;border-radius:3px;'
        'margin-right:6px;vertical-align:-2px}'
        '.legend i.dash{height:0;border-width:0 0 2px 0;border-style:dashed;border-radius:0;width:16px;vertical-align:3px}'
        '.legend i.ghost{border-style:dashed;border-color:#aab2bf;background:none}'
        '.note{padding:6px 12px;font-size:12px;background:#fff8e6;border-bottom:1px solid #f1e3b8;color:#6b5400}'
        '.scroll{overflow:auto;max-height:560px}'
        '</style></head><body><div class="wrap">' + heading + legend + note +
        f'<div class="scroll">{svg}</div></div>'
        '<script>(function(){var s=document.querySelector(".scroll");'
        'var el=document.querySelector(".node.focus")||document.querySelector(".node.root");'
        'if(!s||!el)return;var r=el.getBoundingClientRect(),q=s.getBoundingClientRect();'
        's.scrollLeft+=(r.left+r.width/2)-(q.left+q.width/2);'
        's.scrollTop+=(r.top+r.height/2)-(q.top+q.height/2);})();</script>'
        '</body></html>')
    info.iframe_height = min(height, 560) + 52 + (30 if truncated else 0) + (26 if title else 0) + 18
    return html, info


def _slot_of(node: dict, key: int) -> int:
    try:
        return node["keys"].index(key)
    except ValueError:
        return -1


def _insertion_slot(node: dict, key: int) -> int:
    return sum(1 for k in node["keys"] if k < key)
