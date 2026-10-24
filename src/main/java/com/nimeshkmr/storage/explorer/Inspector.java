package com.nimeshkmr.storage.explorer;

import com.nimeshkmr.storage.DiskManager;
import com.nimeshkmr.storage.InternalNode;
import com.nimeshkmr.storage.LeafNode;
import com.nimeshkmr.storage.LogRecord;
import com.nimeshkmr.storage.Page;
import com.nimeshkmr.storage.WriteAheadLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only views of engine state. Every method parses bytes that the engine itself wrote,
 * using the engine's own LeafNode/InternalNode accessors, and returns immutable DTOs.
 *
 * <p>Callers must hold the tree's read lock (BPlusTree#readConsistently) so a concurrent insert
 * cannot be half-applied while a view is built. Nothing here writes to the database or the WAL.
 */
final class Inspector {

    static final int PAGE_SIZE = 4096;
    static final int MAGIC_NUMBER = 0x42505431;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_VIOLATIONS = 20;

    // Encoded WAL record sizes: 4-byte length prefix + body (13 bytes for COMMIT, 4117 for PAGE_IMAGE).
    // These mirror WriteAheadLog's record layout; WalView.trailingBytes exposes any drift.
    static final int COMMIT_RECORD_BYTES = 4 + 13;
    static final int PAGE_IMAGE_RECORD_BYTES = 4 + 4117;
    static final int MAX_WAL_SCAN_RECORDS = 100_000;

    private Inspector() {}

    // ---------------------------------------------------------------- page access

    private static Page readPage(DiskManager dm, long pageId) throws IOException {
        try {
            return dm.readPage(pageId);
        } catch (IllegalArgumentException e) {
            throw new IOException("Page " + pageId + " is referenced but does not exist in the file", e);
        }
    }

    private static boolean isLeaf(Page page) {
        return page.getByteBuffer().get(0) == 1;
    }

    private static boolean isAllZero(Page page) {
        ByteBuffer bb = page.getByteBuffer();
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (bb.get(i) != 0) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- tree snapshot

    static Dto.TreeSnapshot snapshot(DiskManager dm, long rootId, int leafCap, int internalCap)
            throws IOException {
        long totalPages = dm.getNumPages();
        Walk w = new Walk(dm);
        w.visit(rootId, 0, null, null, rootId);

        // Follow the leaf chain from the leftmost leaf, guarding against cycles.
        List<Long> chain = new ArrayList<>();
        if (!w.leafOrder.isEmpty()) {
            Set<Long> seen = new HashSet<>();
            long cur = w.leafOrder.get(0);
            while (cur != -1L && seen.add(cur) && chain.size() <= totalPages) {
                chain.add(cur);
                Page p = readPage(dm, cur);
                cur = new LeafNode(p).getNextLeafPageID();
            }
        }
        boolean chainOk = chain.equals(w.leafOrder);
        if (!chainOk) {
            w.violation("Leaf chain " + abbreviate(chain) + " differs from tree order " + abbreviate(w.leafOrder));
        }

        int leaves = w.leafOrder.size();
        return new Dto.TreeSnapshot(
                rootId,
                w.maxDepth + 1,
                totalPages,
                w.nodes.size(),
                leaves,
                w.nodes.size() - leaves,
                w.keyCount,
                leafCap,
                internalCap,
                List.copyOf(w.nodes),
                List.copyOf(w.leafOrder),
                List.copyOf(chain),
                chainOk,
                List.copyOf(w.violations));
    }

    private static String abbreviate(List<Long> ids) {
        if (ids.size() <= 8) return ids.toString();
        return ids.subList(0, 8) + "... (" + ids.size() + " pages)";
    }

    /** Depth-first walk that also checks key ordering and separator ranges. */
    private static final class Walk {
        final DiskManager dm;
        final List<Dto.NodeInfo> nodes = new ArrayList<>();
        final List<Long> leafOrder = new ArrayList<>();
        final Set<Long> visited = new HashSet<>();
        final List<String> violations = new ArrayList<>();
        long keyCount;
        int maxDepth;

        Walk(DiskManager dm) {
            this.dm = dm;
        }

        void violation(String msg) {
            if (violations.size() < MAX_VIOLATIONS) violations.add(msg);
        }

        void visit(long pageId, int depth, Integer lower, Integer upper, long rootId) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("Tree deeper than " + MAX_DEPTH + " levels at page " + pageId);
            }
            if (!visited.add(pageId)) {
                throw new IOException("Page " + pageId + " is reachable twice (cycle or shared child)");
            }
            maxDepth = Math.max(maxDepth, depth);
            Page page = readPage(dm, pageId);
            if (isLeaf(page)) {
                LeafNode leaf = new LeafNode(page);
                int n = leaf.getNumKeys();
                if (n < 0 || n > LeafNode.DEFAULT_MAX_ENTRIES) {
                    throw new IOException("Leaf page " + pageId + " has invalid key count " + n);
                }
                List<Integer> keys = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    int k = leaf.getKey(i);
                    if (i > 0 && k <= keys.get(i - 1)) {
                        violation("Leaf " + pageId + " keys not strictly ascending at slot " + i);
                    }
                    if ((lower != null && k < lower) || (upper != null && k >= upper)) {
                        violation("Leaf " + pageId + " key " + k + " outside its parent range ["
                                + (lower == null ? "-inf" : lower) + ", " + (upper == null ? "+inf" : upper) + ")");
                    }
                    keys.add(k);
                }
                keyCount += n;
                leafOrder.add(pageId);
                nodes.add(new Dto.NodeInfo(pageId, "LEAF", depth, n, List.copyOf(keys), List.of(),
                        leaf.getNextLeafPageID(), pageId == rootId));
                return;
            }
            InternalNode node = new InternalNode(page);
            int n = node.getNumKeys();
            if (n < 0 || n > InternalNode.DEFAULT_MAX_KEYS) {
                throw new IOException("Internal page " + pageId + " has invalid key count " + n);
            }
            if (n == 0) {
                violation("Internal page " + pageId + " has 0 keys");
            }
            List<Integer> keys = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int k = node.getKey(i);
                if (i > 0 && k <= keys.get(i - 1)) {
                    violation("Internal " + pageId + " keys not strictly ascending at slot " + i);
                }
                keys.add(k);
            }
            List<Long> children = new ArrayList<>(n + 1);
            for (int i = 0; i <= n; i++) {
                children.add(node.getChildPageID(i));
            }
            nodes.add(new Dto.NodeInfo(pageId, "INTERNAL", depth, n, List.copyOf(keys),
                    List.copyOf(children), -1L, pageId == rootId));
            for (int i = 0; i <= n; i++) {
                Integer lo = i == 0 ? lower : keys.get(i - 1);
                Integer hi = i == n ? upper : keys.get(i);
                visit(children.get(i), depth + 1, lo, hi, rootId);
            }
        }
    }

    // ---------------------------------------------------------------- search trace

    static Dto.SearchTrace trace(DiskManager dm, long rootId, int key, Integer engineValue, int leafCapacity)
            throws IOException {
        List<Dto.SearchStep> path = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long current = rootId;
        int depth = 0;
        while (true) {
            if (depth > MAX_DEPTH || !seen.add(current)) {
                throw new IOException("Search descent did not terminate at page " + current);
            }
            Page page = readPage(dm, current);
            if (isLeaf(page)) {
                LeafNode leaf = new LeafNode(page);
                int n = leaf.getNumKeys();
                if (n < 0 || n > LeafNode.DEFAULT_MAX_ENTRIES) {
                    throw new IOException("Leaf page " + current + " has invalid key count " + n);
                }
                int idx = leaf.findKeyIndex(key);
                boolean found = idx >= 0;
                int pos = found ? idx : -idx - 1;
                Integer value = found ? leaf.getValue(idx) : null;
                Integer before = pos > 0 ? leaf.getKey(pos - 1) : null;
                int afterSlot = found ? idx + 1 : pos;
                Integer after = afterSlot < n ? leaf.getKey(afterSlot) : null;
                boolean engineFound = engineValue != null;
                boolean matches = found == engineFound && (!found || value.equals(engineValue));
                return new Dto.SearchTrace(key, found, value, engineFound, engineValue, matches, rootId,
                        List.copyOf(path), current, depth, n, found ? idx : -1, pos, before, after,
                        n >= leafCapacity, leafCapacity);
            }
            InternalNode node = new InternalNode(page);
            int n = node.getNumKeys();
            if (n < 0 || n > InternalNode.DEFAULT_MAX_KEYS) {
                throw new IOException("Internal page " + current + " has invalid key count " + n);
            }
            int ci = node.findChildIndex(key);
            Integer lower = ci > 0 ? node.getKey(ci - 1) : null;
            Integer upper = ci < n ? node.getKey(ci) : null;
            long next = node.getChildPageID(ci);
            path.add(new Dto.SearchStep(current, depth, n, ci, next, lower, upper));
            current = next;
            depth++;
        }
    }

    // ---------------------------------------------------------------- pages

    /** Page IDs reachable from the root; tolerant of damage (stops descending where it cannot read). */
    static Set<Long> reachable(DiskManager dm, long rootId) {
        Set<Long> out = new LinkedHashSet<>();
        ArrayList<Long> stack = new ArrayList<>();
        stack.add(rootId);
        while (!stack.isEmpty() && out.size() < 1_000_000) {
            long id = stack.remove(stack.size() - 1);
            if (!out.add(id)) continue;
            try {
                Page p = dm.readPage(id);
                if (isLeaf(p)) continue;
                InternalNode node = new InternalNode(p);
                int n = node.getNumKeys();
                if (n < 0 || n > InternalNode.DEFAULT_MAX_KEYS) continue;
                for (int i = n; i >= 0; i--) stack.add(node.getChildPageID(i));
            } catch (IOException | RuntimeException e) {
                // unreadable page: keep what we have; the page view reports the problem itself
            }
        }
        out.add(0L);
        return out;
    }

    static Dto.PageList listPages(DiskManager dm, long rootId, int limit) throws IOException {
        long total = dm.getNumPages();
        Set<Long> reach = reachable(dm, rootId);
        List<Dto.PageSummary> pages = new ArrayList<>();
        long upTo = Math.min(total, limit);
        for (long id = 0; id < upTo; id++) {
            Dto.PageInfo info = pageInfo(dm, rootId, id, reach, false);
            pages.add(new Dto.PageSummary(id, info.role(), info.root(), info.reachable(), info.numKeys()));
        }
        return new Dto.PageList(total, pages.size(), upTo < total, rootId, List.copyOf(pages));
    }

    static Dto.PageInfo pageInfo(DiskManager dm, long rootId, long pageId, boolean hex) throws IOException {
        return pageInfo(dm, rootId, pageId, reachable(dm, rootId), hex);
    }

    private static Dto.PageInfo pageInfo(DiskManager dm, long rootId, long pageId, Set<Long> reach, boolean hex)
            throws IOException {
        long total = dm.getNumPages();
        if (pageId < 0 || pageId >= total) {
            throw ApiException.notFound("PAGE_NOT_FOUND",
                    "Page " + pageId + " does not exist (file has " + total + " pages)");
        }
        Page page = dm.readPage(pageId);
        ByteBuffer bb = page.getByteBuffer();
        boolean zero = isAllZero(page);
        boolean reachable = reach.contains(pageId);
        String hexText = hex ? hexPreview(bb) : null;
        long offset = pageId * PAGE_SIZE;

        if (pageId == 0) {
            int magic = bb.getInt(0);
            boolean ok = magic == MAGIC_NUMBER;
            return new Dto.PageInfo(0, PAGE_SIZE, offset, ok ? "METADATA" : "INVALID", false, true, zero, 0,
                    List.of(), List.of(), List.of(), -1L, bb.getLong(4), ok,
                    String.format("0x%08X", magic), hexText,
                    ok ? "Tree metadata: magic number and root page ID."
                       : "Page 0 does not start with the expected magic number 0x42505431.");
        }
        if (zero) {
            return new Dto.PageInfo(pageId, PAGE_SIZE, offset, "UNUSED", false, reachable, true, 0,
                    List.of(), List.of(), List.of(), -1L, null, null, null, hexText,
                    "All bytes are zero: allocated but never written by a committed transaction.");
        }
        if (isLeaf(page)) {
            LeafNode leaf = new LeafNode(page);
            int n = leaf.getNumKeys();
            if (n < 0 || n > LeafNode.DEFAULT_MAX_ENTRIES) {
                return invalid(pageId, offset, reachable, rootId, hexText, "Leaf key count out of range: " + n);
            }
            List<Integer> keys = new ArrayList<>(n);
            List<Integer> values = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                keys.add(leaf.getKey(i));
                values.add(leaf.getValue(i));
            }
            return new Dto.PageInfo(pageId, PAGE_SIZE, offset, "LEAF", pageId == rootId, reachable, false, n,
                    List.copyOf(keys), List.copyOf(values), List.of(), leaf.getNextLeafPageID(),
                    null, null, null, hexText,
                    reachable ? null : "Not reachable from the current root.");
        }
        InternalNode node = new InternalNode(page);
        int n = node.getNumKeys();
        if (n < 0 || n > InternalNode.DEFAULT_MAX_KEYS) {
            return invalid(pageId, offset, reachable, rootId, hexText, "Internal key count out of range: " + n);
        }
        List<Integer> keys = new ArrayList<>(n);
        List<Long> children = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) keys.add(node.getKey(i));
        for (int i = 0; i <= n; i++) children.add(node.getChildPageID(i));
        return new Dto.PageInfo(pageId, PAGE_SIZE, offset, "INTERNAL", pageId == rootId, reachable, false, n,
                List.copyOf(keys), List.of(), List.copyOf(children), -1L, null, null, null, hexText,
                reachable ? null : "Not reachable from the current root.");
    }

    private static Dto.PageInfo invalid(long pageId, long offset, boolean reachable, long rootId,
            String hexText, String note) {
        return new Dto.PageInfo(pageId, PAGE_SIZE, offset, "INVALID", pageId == rootId, reachable, false, 0,
                List.of(), List.of(), List.of(), -1L, null, null, null, hexText, note);
    }

    private static String hexPreview(ByteBuffer bb) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 6; row++) {
            sb.append(String.format("%04x:", row * 16));
            for (int i = 0; i < 16; i++) {
                sb.append(String.format(" %02x", bb.get(row * 16 + i) & 0xff));
            }
            if (row < 5) sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- WAL

    static int encodedSize(LogRecord r) {
        return r.getType() == LogRecord.COMMIT ? COMMIT_RECORD_BYTES : PAGE_IMAGE_RECORD_BYTES;
    }

    /**
     * Reads the WAL through {@link WriteAheadLog#readNextRecord}, which never modifies the file
     * (unlike scanRecords, which truncates an incomplete tail). Returns up to {@code limit}
     * records starting at the record that begins at byte {@code offset}, plus a summary over the
     * whole log. A transaction is reported as "commit record present" exactly when a COMMIT record
     * for its ID is in the log: the rule RecoveryManager uses to decide what to replay.
     */
    static Dto.WalView walView(WriteAheadLog wal, long offset, int limit) throws IOException {
        long size = wal.getLogSize();

        // Pass 1: whole-log scan for the commit set and counters.
        Set<Long> committed = new HashSet<>();
        Set<Long> txns = new LinkedHashSet<>();
        int images = 0, commits = 0, total = 0;
        long maxTxn = 0, pos = 0;
        boolean truncated = false;
        while (pos < size) {
            if (total >= MAX_WAL_SCAN_RECORDS) {
                truncated = true;
                break;
            }
            LogRecord r = wal.readNextRecord(pos);
            if (r == null) break; // incomplete trailing record
            int sz = encodedSize(r);
            if (r.getType() == LogRecord.COMMIT) {
                commits++;
                committed.add(r.getTransactionID());
            } else {
                images++;
            }
            txns.add(r.getTransactionID());
            maxTxn = Math.max(maxTxn, r.getTransactionID());
            total++;
            pos += sz;
        }
        long scannedEnd = pos;

        List<Long> uncommitted = new ArrayList<>();
        for (long t : txns) {
            if (!committed.contains(t) && uncommitted.size() < 50) uncommitted.add(t);
        }

        // Pass 2: collect the requested window.
        List<Dto.WalRecordInfo> out = new ArrayList<>();
        boolean offsetValid = offset == 0 || offset == scannedEnd;
        long next = -1;
        pos = 0;
        int index = 0;
        while (pos < scannedEnd) {
            LogRecord r = wal.readNextRecord(pos);
            if (r == null) break;
            int sz = encodedSize(r);
            if (pos == offset) offsetValid = true;
            if (pos >= offset && out.size() < limit) {
                out.add(describe(index, pos, sz, r, committed.contains(r.getTransactionID())));
            } else if (pos >= offset && out.size() >= limit) {
                next = pos;
                break;
            }
            pos += sz;
            index++;
        }
        if (!offsetValid) {
            throw ApiException.invalid("offset " + offset + " is not the start of a WAL record");
        }
        Dto.WalSummary summary = new Dto.WalSummary(total, images, commits, txns.size(),
                (int) txns.stream().filter(committed::contains).count(), List.copyOf(uncommitted), maxTxn, truncated);
        return new Dto.WalView(size, offset, next, List.copyOf(out), summary, size - scannedEnd);
    }

    private static Dto.WalRecordInfo describe(int index, long offset, int size, LogRecord r, boolean committed) {
        if (r.getType() == LogRecord.COMMIT) {
            return new Dto.WalRecordInfo(index, offset, size, "COMMIT", r.getTransactionID(), null,
                    null, null, null, committed);
        }
        byte[] img = r.getPageImage();
        ByteBuffer bb = ByteBuffer.wrap(img);
        String nodeType;
        Integer numKeys = null;
        Long rootId = null;
        if (r.getPageID() == 0) {
            nodeType = "METADATA";
            rootId = bb.getLong(4);
        } else if (img[0] == 1) {
            nodeType = "LEAF";
            numKeys = bb.getInt(1);
        } else if (img[0] == 0) {
            nodeType = "INTERNAL_OR_EMPTY";
            numKeys = bb.getInt(1);
        } else {
            nodeType = "UNKNOWN";
        }
        return new Dto.WalRecordInfo(index, offset, size, "PAGE_IMAGE", r.getTransactionID(), r.getPageID(),
                nodeType, numKeys, rootId, committed);
    }

    /** Small helper for demo output: a LinkedHashMap literal builder. */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
