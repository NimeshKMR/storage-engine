package com.nimeshkmr.storage.explorer;

import static org.junit.jupiter.api.Assertions.*;

import com.nimeshkmr.storage.InsertReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerServiceTest {

    @TempDir
    Path tempDir;

    private ExplorerService service() throws IOException {
        return new ExplorerService(tempDir.resolve("data"));
    }

    private static ApiException expectApi(int status, String code, Executable1 e) {
        ApiException ex = assertThrows(ApiException.class, e::run);
        assertEquals(status, ex.status(), ex.getMessage());
        if (code != null) assertEquals(code, ex.code());
        return ex;
    }

    @FunctionalInterface
    interface Executable1 extends org.junit.jupiter.api.function.Executable {
        void run() throws Throwable;
        @Override default void execute() throws Throwable { run(); }
    }

    // ---- 1. initialization / names / invalid input ------------------------------------

    @Test
    void createInitializesAnEmptyTreeWithDemoCapacities() throws IOException {
        try (ExplorerService s = service()) {
            Dto.DatabaseStatus st = s.create("demo", 4, 4);
            assertEquals("OPEN", st.state());
            assertEquals(4, st.leafCapacity());
            assertEquals(4, st.internalCapacity());
            assertEquals(Long.valueOf(2), st.totalPages()); // metadata page + root leaf
            assertEquals(Long.valueOf(1), st.rootPageId());
            assertEquals(Boolean.FALSE, st.recoveryRequired());

            Dto.TreeSnapshot t = s.tree("demo");
            assertEquals(1, t.height());
            assertEquals(1, t.nodeCount());
            assertEquals(0L, t.keyCount());
            assertEquals("LEAF", t.nodes().get(0).type());
            assertTrue(t.nodes().get(0).root());
            assertTrue(t.invariantViolations().isEmpty());
        }
    }

    @Test
    void unsafeOrMalformedNamesAreRejected() throws IOException {
        try (ExplorerService s = service()) {
            for (String bad : new String[] {"../evil", "a/b", "A", "", " ", "con", "nul", "x".repeat(40),
                    "name.db", "..", "-start"}) {
                expectApi(400, "INVALID_INPUT", () -> s.create(bad, 4, 4));
            }
            expectApi(400, "INVALID_INPUT", () -> s.create(null, 4, 4));
            assertFalse(Files.exists(tempDir.resolve("evil.db")));
            try (var files = Files.list(tempDir.resolve("data"))) {
                assertEquals(0L, files.count(), "rejected names must not create files");
            }
        }
    }

    @Test
    void invalidCapacitiesAndIntegersAreRejected() throws IOException {
        try (ExplorerService s = service()) {
            expectApi(400, "INVALID_INPUT", () -> s.create("x1", 2, 4));
            expectApi(400, "INVALID_INPUT", () -> s.create("x1", 4, 341));
            expectApi(400, "INVALID_INPUT", () -> s.create("x1", 511, 4));
            expectApi(400, "INVALID_INPUT", () -> ExplorerService.parseInt("12abc", "key"));
            expectApi(400, "INVALID_INPUT", () -> ExplorerService.parseInt("2147483648", "key"));
            expectApi(400, "INVALID_INPUT", () -> ExplorerService.parseInt(null, "key"));
            expectApi(400, "INVALID_INPUT", () -> ExplorerService.parseInt("  ", "key"));
            assertEquals(-5, ExplorerService.parseInt(" -5 ", "key"));
        }
    }

    @Test
    void duplicateCreateDoesNotTouchTheExistingDatabase() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            s.insert("demo", 1, 10);
            expectApi(409, "ALREADY_EXISTS", () -> s.create("demo", 8, 8));
            assertEquals(1L, s.tree("demo").keyCount());
            assertEquals(4, s.status("demo").leafCapacity());
        }
    }

    @Test
    void unknownAndClosedDatabasesGiveDistinctErrors() throws IOException {
        try (ExplorerService s = service()) {
            expectApi(404, "DATABASE_NOT_FOUND", () -> s.tree("ghost"));
            expectApi(404, "DATABASE_NOT_FOUND", () -> s.openDatabase("ghost"));
            s.create("demo", 4, 4);
            s.closeDatabase("demo");
            expectApi(409, "NOT_OPEN", () -> s.tree("demo"));
            expectApi(409, "NOT_OPEN", () -> s.closeDatabase("demo"));
            s.openDatabase("demo");
            expectApi(409, "ALREADY_OPEN", () -> s.openDatabase("demo"));
        }
    }

    @Test
    void openRefusesToCreateMissingFiles() throws IOException {
        Path data = tempDir.resolve("data");
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            s.closeDatabase("demo");
            Files.delete(data.resolve("demo.db"));
            expectApi(409, "FILES_MISSING", () -> s.openDatabase("demo"));
            assertFalse(Files.exists(data.resolve("demo.db")), "open must not silently recreate the file");
        }
    }

    @Test
    void deleteRequiresMatchingConfirmation() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            expectApi(400, "INVALID_INPUT", () -> s.delete("demo", null));
            expectApi(400, "INVALID_INPUT", () -> s.delete("demo", "other"));
            assertEquals(1, s.list().size());
            s.delete("demo", "demo");
            assertEquals(0, s.list().size());
            try (var files = Files.list(tempDir.resolve("data"))) {
                assertEquals(0L, files.count());
            }
        }
    }

    // ---- 2/3. insert, search, missing key ----------------------------------------------

    @Test
    void insertThenSearchReturnsTheEnginesValue() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            InsertReport r = s.insert("demo", 42, 420);
            assertEquals(InsertReport.Outcome.INSERTED, r.outcome());
            assertEquals(PageStateNames.APPLIED, r.transactionState().name());

            Dto.SearchTrace t = s.search("demo", 42);
            assertTrue(t.found());
            assertEquals(Integer.valueOf(420), t.value());
            assertTrue(t.engineFound());
            assertTrue(t.traceMatchesEngine());
            assertEquals(0, t.foundIndex());
            assertTrue(t.path().isEmpty(), "a single-leaf tree has no internal nodes on the path");
            assertEquals(t.rootPageId(), t.leafPageId());

            InsertReport upd = s.insert("demo", 42, 421);
            assertEquals(InsertReport.Outcome.UPDATED, upd.outcome());
            assertEquals(Integer.valueOf(420), upd.previousValue());
            assertEquals(Integer.valueOf(421), s.search("demo", 42).value());
        }
    }

    private static final class PageStateNames { static final String APPLIED = "APPLIED"; }

    @Test
    void missingKeySearchReportsWhereItWouldBeInserted() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            for (int k : new int[] {10, 20, 30}) s.insert("demo", k, k);
            Dto.SearchTrace t = s.search("demo", 25);
            assertFalse(t.found());
            assertNull(t.value());
            assertFalse(t.engineFound());
            assertTrue(t.traceMatchesEngine());
            assertEquals(-1, t.foundIndex());
            assertEquals(2, t.insertionIndex());
            assertEquals(Integer.valueOf(20), t.keyBefore());
            assertEquals(Integer.valueOf(30), t.keyAfter());
            assertFalse(t.leafAtCapacity());

            s.insert("demo", 40, 40);
            assertTrue(s.search("demo", 99).leafAtCapacity() || s.tree("demo").height() > 1);
        }
    }

    @Test
    void extremeIntegerKeysWork() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            s.insert("demo", Integer.MIN_VALUE, 1);
            s.insert("demo", Integer.MAX_VALUE, 2);
            s.insert("demo", 0, 3);
            assertEquals(Integer.valueOf(1), s.search("demo", Integer.MIN_VALUE).value());
            assertEquals(Integer.valueOf(2), s.search("demo", Integer.MAX_VALUE).value());
        }
    }

    // ---- 4-7. snapshots after splits ---------------------------------------------------

    @Test
    void snapshotAfterLeafSplitHasCorrectPagesAndChildren() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            long originalRoot = s.tree("demo").rootPageId();
            InsertReport last = null;
            for (int k = 1; k <= 5; k++) last = s.insert("demo", k, k * 10);

            Dto.TreeSnapshot t = s.tree("demo");
            assertEquals(2, t.height());
            assertEquals(3, t.nodeCount());
            assertEquals(2, t.leafCount());
            assertEquals(1, t.internalCount());
            assertEquals(5L, t.keyCount());
            assertEquals(last.rootPageID(), t.rootPageId());
            assertNotEquals(originalRoot, t.rootPageId());
            assertEquals(4L, t.totalPages()); // meta, original leaf, new right leaf, new root

            Dto.NodeInfo root = byId(t, t.rootPageId());
            assertEquals("INTERNAL", root.type());
            assertTrue(root.root());
            assertEquals(List.of(3), root.keys());
            InsertReport.Split split = last.splits().get(0);
            assertEquals(List.of(split.leftPageID(), split.newRightPageID()), root.childPageIds());

            Dto.NodeInfo left = byId(t, split.leftPageID());
            Dto.NodeInfo right = byId(t, split.newRightPageID());
            assertEquals(List.of(1, 2), left.keys());
            assertEquals(List.of(3, 4, 5), right.keys());
            assertEquals(right.pageId(), left.nextLeafPageId());
            assertEquals(-1L, right.nextLeafPageId());
            assertEquals(List.of(left.pageId(), right.pageId()), t.leafOrder());
            assertEquals(t.leafOrder(), t.leafChain());
            assertTrue(t.leafChainConsistent());
            assertTrue(t.invariantViolations().isEmpty(), t.invariantViolations().toString());
            assertEquals(1, root.depth() + 1);
            assertEquals(1, left.depth());
        }
    }

    @Test
    void snapshotAfterInternalAndRootSplitHasThreeLevels() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            long previousRoot = s.tree("demo").rootPageId();
            int rootChanges = 0;
            int internalSplits = 0;
            for (int k = 1; k <= 120; k++) {
                InsertReport r = s.insert("demo", k, k);
                if (r.rootChanged()) rootChanges++;
                for (InsertReport.Split sp : r.splits()) if (sp.kind() == InsertReport.NodeKind.INTERNAL) internalSplits++;
            }
            Dto.TreeSnapshot t = s.tree("demo");
            assertTrue(t.height() >= 3, "height was " + t.height());
            assertTrue(internalSplits >= 1);
            assertTrue(rootChanges >= 2, "root should have changed at least twice, was " + rootChanges);
            assertNotEquals(previousRoot, t.rootPageId());
            assertEquals(120L, t.keyCount());
            assertTrue(t.leafChainConsistent());
            assertTrue(t.invariantViolations().isEmpty(), t.invariantViolations().toString());
            assertEquals(1L, t.nodes().stream().filter(Dto.NodeInfo::root).count());

            // Every parent's children exist, point only downward, and are listed exactly once.
            Map<Long, Dto.NodeInfo> byId = new TreeMap<>();
            for (Dto.NodeInfo n : t.nodes()) byId.put(n.pageId(), n);
            List<Long> referenced = new ArrayList<>();
            for (Dto.NodeInfo n : t.nodes()) {
                if (n.type().equals("INTERNAL")) {
                    assertEquals(n.numKeys() + 1, n.childPageIds().size());
                    for (long c : n.childPageIds()) {
                        assertTrue(byId.containsKey(c), "child " + c + " missing");
                        assertEquals(n.depth() + 1, byId.get(c).depth());
                        referenced.add(c);
                    }
                    assertTrue(n.numKeys() <= 4);
                } else {
                    assertTrue(n.numKeys() <= 4);
                }
            }
            assertEquals(t.nodeCount() - 1, referenced.size());
            assertEquals(referenced.size(), referenced.stream().distinct().count());
        }
    }

    @Test
    void shuffledBulkInsertKeepsAllKeysSearchableAndInvariantsClean() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            Dto.BulkResult b = s.bulk("demo", 0, 500, 3, "shuffled", 123L, 10);
            assertEquals(500, b.inserted());
            assertEquals(0, b.updated());
            assertTrue(b.leafSplits() > 50);
            Dto.TreeSnapshot t = s.tree("demo");
            assertEquals(500L, t.keyCount());
            assertTrue(t.invariantViolations().isEmpty(), t.invariantViolations().toString());
            assertTrue(t.leafChainConsistent());
            for (int i = 0; i < 500; i += 7) {
                Dto.SearchTrace tr = s.search("demo", i * 3);
                assertTrue(tr.found());
                assertEquals(Integer.valueOf(i * 3 * 10), tr.value());
                assertTrue(tr.traceMatchesEngine());
                assertEquals(t.height() - 1, tr.path().size());
            }
            assertFalse(s.search("demo", 1).found());
        }
    }

    @Test
    void searchPathBoundsContainTheKey() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            s.bulk("demo", 1, 60, 1, "ascending", 1L, 1);
            for (int k : new int[] {1, 17, 33, 60, 100, -5}) {
                Dto.SearchTrace t = s.search("demo", k);
                for (Dto.SearchStep st : t.path()) {
                    if (st.lowerBound() != null) assertTrue(k >= st.lowerBound(), "key " + k + " below bound");
                    if (st.upperBound() != null) assertTrue(k < st.upperBound(), "key " + k + " above bound");
                }
            }
        }
    }

    @Test
    void bulkRejectsUnsafeParameters() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            expectApi(400, null, () -> s.bulk("demo", 0, 0, 1, "ascending", 1, 1));
            expectApi(400, null, () -> s.bulk("demo", 0, 5000, 1, "ascending", 1, 1));
            expectApi(400, null, () -> s.bulk("demo", 0, 10, 0, "ascending", 1, 1));
            expectApi(400, null, () -> s.bulk("demo", 0, 10, 1, "sideways", 1, 1));
            expectApi(400, null, () -> s.bulk("demo", Integer.MAX_VALUE - 2, 10, 1, "ascending", 1, 1));
            expectApi(400, null, () -> s.bulk("demo", 1_000_000_000, 10, 1, "ascending", 1, 10));
            assertEquals(0L, s.tree("demo").keyCount(), "rejected bulk requests must insert nothing");
        }
    }

    // ---- pages -------------------------------------------------------------------------

    @Test
    void pageViewMatchesTheTree() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            s.bulk("demo", 1, 9, 1, "ascending", 1, 10);
            Dto.TreeSnapshot t = s.tree("demo");

            Dto.PageInfo meta = s.page("demo", 0, true);
            assertEquals("METADATA", meta.role());
            assertEquals(Boolean.TRUE, meta.magicOk());
            assertEquals("0x42505431", meta.magicHex());
            assertEquals(Long.valueOf(t.rootPageId()), meta.metadataRootPageId());
            assertEquals(4096, meta.pageSize());
            assertNotNull(meta.hexPreview());

            Dto.PageInfo rootPage = s.page("demo", t.rootPageId(), false);
            assertEquals("INTERNAL", rootPage.role());
            assertTrue(rootPage.root());
            assertTrue(rootPage.reachable());
            assertEquals(byId(t, t.rootPageId()).keys(), rootPage.keys());
            assertEquals(byId(t, t.rootPageId()).childPageIds(), rootPage.childPageIds());
            assertNull(rootPage.hexPreview());

            for (long leafId : t.leafOrder()) {
                Dto.PageInfo leaf = s.page("demo", leafId, false);
                assertEquals("LEAF", leaf.role());
                assertEquals(byId(t, leafId).keys(), leaf.keys());
                assertEquals(leaf.keys().size(), leaf.values().size());
                for (int i = 0; i < leaf.keys().size(); i++) assertEquals(leaf.keys().get(i) * 10, leaf.values().get(i));
                assertEquals(leafId * 4096, leaf.byteOffset());
            }
            Dto.PageList list = s.pages("demo");
            assertEquals(t.totalPages(), list.totalPages());
            assertEquals(t.totalPages(), (long) list.returned());
            assertFalse(list.truncated());
            assertEquals(t.nodeCount() + 1, list.pages().stream().filter(Dto.PageSummary::reachable).count());
        }
    }

    @Test
    void pageOutOfRangeIsNotFound() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            expectApi(404, "PAGE_NOT_FOUND", () -> s.page("demo", 99, false));
            expectApi(404, "PAGE_NOT_FOUND", () -> s.page("demo", -1, false));
        }
    }

    // ---- WAL ---------------------------------------------------------------------------

    @Test
    void walViewListsPageImagesAndCommitsInOrder() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            InsertReport r = s.insert("demo", 1, 10);

            Dto.WalView w = s.wal("demo", 0, 100);
            assertEquals(w.summary().totalRecords(), w.records().size());
            assertEquals(w.summary().pageImageRecords() + w.summary().commitRecords(), w.records().size());
            // Transaction 1 initialised the tree (2 pages), transaction 2 is our insert (1 page).
            assertEquals(2, w.summary().transactions());
            assertEquals(2, w.summary().commitRecords());
            assertEquals(3, w.summary().pageImageRecords());
            assertEquals(2, w.summary().transactionsWithCommitRecord());
            assertTrue(w.summary().transactionsWithoutCommitRecord().isEmpty());
            assertEquals(0L, w.trailingBytes());
            assertEquals(w.walBytes(), w.records().get(w.records().size() - 1).offset()
                    + w.records().get(w.records().size() - 1).sizeBytes());

            // Within each transaction, page images precede the commit record.
            Dto.WalRecordInfo last = w.records().get(w.records().size() - 1);
            assertEquals("COMMIT", last.type());
            assertEquals(r.transactionID(), last.transactionId());
            for (Dto.WalRecordInfo rec : w.records()) {
                if (rec.type().equals("PAGE_IMAGE")) {
                    assertNotNull(rec.pageId());
                    assertTrue(rec.commitRecordPresent());
                } else {
                    assertNull(rec.pageId());
                }
            }
            long expectedOffset = 0;
            for (Dto.WalRecordInfo rec : w.records()) {
                assertEquals(expectedOffset, rec.offset());
                expectedOffset += rec.sizeBytes();
            }
        }
    }

    @Test
    void walPagingUsesRecordBoundaries() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            s.bulk("demo", 1, 10, 1, "ascending", 1, 1);
            Dto.WalView all = s.wal("demo", 0, 500);
            Dto.WalView p1 = s.wal("demo", 0, 5);
            assertEquals(5, p1.records().size());
            assertTrue(p1.nextOffset() > 0);
            Dto.WalView p2 = s.wal("demo", p1.nextOffset(), 5);
            assertEquals(all.records().get(5).offset(), p2.records().get(0).offset());
            expectApi(400, "INVALID_INPUT", () -> s.wal("demo", 7, 5));
            expectApi(400, "INVALID_INPUT", () -> s.wal("demo", -1, 5));
            expectApi(400, "INVALID_INPUT", () -> s.wal("demo", 0, 0));
            expectApi(400, "INVALID_INPUT", () -> s.wal("demo", 0, 100000));
        }
    }

    @Test
    void walViewNeverModifiesTheLog() throws IOException {
        Path data = tempDir.resolve("data");
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            s.bulk("demo", 1, 20, 1, "ascending", 1, 1);
            byte[] before = Files.readAllBytes(data.resolve("demo.wal"));
            s.wal("demo", 0, 500);
            s.tree("demo");
            s.pages("demo");
            assertArrayEquals(before, Files.readAllBytes(data.resolve("demo.wal")));
        }
    }

    // ---- 10. persistence ---------------------------------------------------------------

    @Test
    void closeAndReopenPreservesTreeAndKeys() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            s.bulk("demo", 1, 80, 1, "shuffled", 5L, 10);
            Dto.TreeSnapshot before = s.tree("demo");
            s.closeDatabase("demo");

            Dto.DatabaseStatus closed = s.status("demo");
            assertEquals("CLOSED", closed.state());
            assertNull(closed.rootPageId());
            assertTrue(closed.dbBytes() > 0);

            Dto.DatabaseStatus reopened = s.openDatabase("demo");
            assertEquals("OPEN", reopened.state());
            Dto.TreeSnapshot after = s.tree("demo");
            assertEquals(Json.write(before), Json.write(after));
            assertEquals(Integer.valueOf(250), s.search("demo", 25).value());
        }
    }

    @Test
    void anotherServiceInstanceCanOpenTheSameFiles() throws IOException {
        Path data = tempDir.resolve("data");
        try (ExplorerService s = new ExplorerService(data)) {
            s.create("demo", 4, 4);
            s.bulk("demo", 1, 30, 1, "ascending", 1, 10);
        }
        try (ExplorerService s = new ExplorerService(data)) {
            assertEquals("CLOSED", s.list().get(0).state());
            s.openDatabase("demo");
            assertEquals(30L, s.tree("demo").keyCount());
            assertEquals(Integer.valueOf(300), s.search("demo", 30).value());
        }
    }

    // ---- 12. concurrency ---------------------------------------------------------------

    @Test
    void snapshotsStayConsistentWhileWritersInsert() throws Exception {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            int writers = 3;
            int perWriter = 150;
            ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
            try {
                for (int w = 0; w < writers; w++) {
                    final int id = w;
                    futures.add(pool.submit(() -> {
                        go.await();
                        for (int i = 0; i < perWriter; i++) s.insert("demo", id * 10_000 + i, i);
                        return null;
                    }));
                }
                for (int r = 0; r < 2; r++) {
                    futures.add(pool.submit(() -> {
                        go.await();
                        long lastCount = -1;
                        int snapshots = 0;
                        while (snapshots < 60 || lastCount < (long) writers * perWriter) {
                            Dto.TreeSnapshot t = s.tree("demo");
                            snapshots++;
                            if (!t.invariantViolations().isEmpty()) problems.add("violations: " + t.invariantViolations());
                            if (!t.leafChainConsistent()) problems.add("leaf chain inconsistent");
                            if (t.keyCount() < lastCount) problems.add("key count went backwards");
                            long inLeaves = t.nodes().stream().filter(n -> n.type().equals("LEAF")).mapToLong(Dto.NodeInfo::numKeys).sum();
                            if (inLeaves != t.keyCount()) problems.add("count mismatch");
                            lastCount = t.keyCount();
                            Dto.SearchTrace tr = s.search("demo", 5);
                            if (!tr.traceMatchesEngine()) problems.add("trace differs from engine search");
                            if (snapshots > 5000) break;
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : futures) f.get(120, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertTrue(problems.isEmpty(), problems.toString());
            assertEquals((long) writers * perWriter, s.tree("demo").keyCount());
        }
    }

    // ---- JSON --------------------------------------------------------------------------

    @Test
    void jsonRoundTripsDtosAndEscapes() throws IOException {
        try (ExplorerService s = service()) {
            s.create("demo", 4, 4);
            s.insert("demo", 1, 2);
            @SuppressWarnings("unchecked")
            Map<String, Object> tree = (Map<String, Object>) Json.parse(Json.write(s.tree("demo")));
            assertEquals(1L, tree.get("keyCount"));
            assertEquals(Boolean.TRUE, tree.get("leafChainConsistent"));
            assertTrue(tree.get("nodes") instanceof List);
        }
        assertEquals("\"a\\\"b\\n\\u0001\"", Json.write("a\"b\n\u0001"));
        assertEquals("a\"b\n\u0001", Json.parse("\"a\\\"b\\n\\u0001\""));
        assertEquals("null", Json.write(null));
        assertEquals("rootPageId", Json.fieldName("rootPageID"));
        assertEquals("transactionId", Json.fieldName("transactionID"));
        assertEquals("pageId", Json.fieldName("pageId"));
        try (ExplorerService s = service()) {
            s.create("other", 4, 4);
            String report = Json.write(s.insert("other", 1, 2));
            assertTrue(report.contains("\"rootPageId\":") && report.contains("\"leafPageId\":"), report);
            assertFalse(report.contains("ID\":"), "no field name should end in ID: " + report);
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private static Dto.NodeInfo byId(Dto.TreeSnapshot t, long id) {
        return t.nodes().stream().filter(n -> n.pageId() == id).findFirst()
                .orElseThrow(() -> new AssertionError("page " + id + " not in snapshot"));
    }
}
