import re
import xml.etree.ElementTree as ET

from tree_svg import render_tree_html


def _svg(html: str) -> ET.Element:
    m = re.search(r"<svg.*?</svg>", html, re.S)
    assert m, "no svg in output"
    return ET.fromstring(m.group(0))      # raises if the SVG is not well-formed XML


def _nodes(root: ET.Element):
    return [g for g in root.iter("{http://www.w3.org/2000/svg}g") if g.get("class", "").startswith("node")]


def test_empty_tree_renders_single_empty_root_leaf(client, db_name):
    snap = client.tree(db_name)
    html, info = render_tree_html(snap)
    nodes = _nodes(_svg(html))
    assert len(nodes) == 1 and "root" in nodes[0].get("class")
    assert info.total_leaves == 1 and not info.truncated
    assert "ROOT" in html and "LEAF" in html


def test_every_engine_page_in_snapshot_is_drawn_when_small(client, db_name):
    client.bulk(db_name, 1, 14, 1, "shuffled", 3, 1)
    snap = client.tree(db_name)
    html, info = render_tree_html(snap)
    drawn = {int(g.get("data-page")) for g in _nodes(_svg(html))}
    assert drawn == {n["pageId"] for n in snap["nodes"]}
    assert info.nodes_drawn == snap["nodeCount"]
    for n in snap["nodes"]:                     # every key of every node appears in the markup
        for k in n["keys"]:
            assert f">{k}</text>" in html


def test_large_tree_is_windowed_and_says_so(client, db_name):
    client.bulk(db_name, 1, 600, 1, "ascending", 1, 1)
    snap = client.tree(db_name)
    html, info = render_tree_html(snap, max_leaves=6)
    assert snap["leafCount"] > 6
    assert info.truncated and info.shown_leaves == 6
    leaves = [g for g in _nodes(_svg(html)) if "LEAF" in ET.tostring(g, encoding="unicode")]
    assert len(leaves) == 6
    assert f"of {snap['leafCount']}" in html
    assert ET.fromstring(re.search(r"<svg.*?</svg>", html, re.S).group(0)) is not None


def test_window_follows_focus_leaf_and_manual_start(client, db_name):
    client.bulk(db_name, 1, 300, 1, "ascending", 1, 1)
    snap = client.tree(db_name)
    target = snap["leafOrder"][40]
    _, info = render_tree_html(snap, max_leaves=6, focus_leaf=target)
    assert info.window_start <= 40 < info.window_end
    _, info2 = render_tree_html(snap, max_leaves=6, window_start=10)
    assert (info2.window_start, info2.window_end) == (10, 16)
    _, info3 = render_tree_html(snap, max_leaves=6, window_start=10_000)   # clamped, not an error
    assert info3.window_end == snap["leafCount"]


def test_search_highlight_marks_path_and_key(client, db_name):
    client.bulk(db_name, 1, 40, 1, "ascending", 1, 1)
    snap = client.tree(db_name)
    tr = client.search(db_name, 21)
    path = [s["pageId"] for s in tr["path"]] + [tr["leafPageId"]]
    html, _ = render_tree_html(snap, path_pages=path, leaf_page=tr["leafPageId"], focus_key=21, found=True)
    assert html.count('class="node focus"') + html.count('class="node root focus"') == 1
    assert "#e08a00" in html                     # highlight colour used
    miss = client.search(db_name, 1000)
    html2, _ = render_tree_html(snap, path_pages=[s["pageId"] for s in miss["path"]] + [miss["leafPageId"]],
                                leaf_page=miss["leafPageId"], focus_key=1000, found=False)
    _svg(html2)                                  # still valid XML with the insertion caret drawn


def test_badges_and_default_engine_capacity_abbreviation(client):
    name = "wide" + "x"
    try:
        client.create(name, 510, 340)
        client.bulk(name, 1, 600, 1, "ascending", 1, 1)    # one leaf holds >8 keys -> abbreviated
        snap = client.tree(name)
        html, _ = render_tree_html(snap, badges={snap["rootPageId"]: "NEW ROOT"})
        _svg(html)
        assert "NEW ROOT" in html and "keys" in html and ">+" in html
    finally:
        try:
            client.delete(name, name)
        except Exception:
            pass
