package com.nimeshkmr.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the additive engine changes: demo capacities, insertWithReport, readConsistently. */
class InsertReportTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultCapacitiesAreUnchanged() throws IOException {
        try (DiskManager dm = new DiskManager(tempDir.resolve("a.db"))) {
            BPlusTree tree = new BPlusTree(dm);
            assertEquals(510, tree.getLeafCapacity());
            assertEquals(340, tree.getInternalCapacity());
        }
    }

    @Test
    void invalidCapacitiesAreRejected() throws IOException {
        try (DiskManager dm = new DiskManager(tempDir.resolve("b.db"))) {
            assertThrows(IllegalArgumentException.class, () -> new BPlusTree(dm, null, 2, 4));
            assertThrows(IllegalArgumentException.class, () -> new BPlusTree(dm, null, 4, 2));
            assertThrows(IllegalArgumentException.class, () -> new BPlusTree(dm, null, 511, 4));
            assertThrows(IllegalArgumentException.class, () -> new BPlusTree(dm, null, 4, 341));
        }
    }

    @Test
    void reportDistinguishesInsertFromUpdate() throws IOException {
        try (DiskManager dm = new DiskManager(tempDir.resolve("c.db"))) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            InsertReport first = tree.insertWithReport(7, 70);
            assertEquals(InsertReport.Outcome.INSERTED, first.outcome());
            assertNull(first.previousValue());
            assertTrue(first.splits().isEmpty());
            assertFalse(first.rootChanged());
            assertEquals(-1L, first.transactionID());
            assertNull(first.transactionState());

            InsertReport second = tree.insertWithReport(7, 71);
            assertEquals(InsertReport.Outcome.UPDATED, second.outcome());
            assertEquals(Integer.valueOf(70), second.previousValue());
            assertEquals(Integer.valueOf(71), tree.search(7));
        }
    }

    @Test
    void leafSplitIsReportedWithSeparatorAndNewRoot() throws IOException {
        try (DiskManager dm = new DiskManager(tempDir.resolve("d.db"))) {
            BPlusTree tree = new BPlusTree(dm, null, 4, 4);
            tree.initialize();
            long originalRoot = tree.getRootPageID();

            for (int k = 1; k <= 4; k++) {
                assertTrue(tree.insertWithReport(k, k).splits().isEmpty(), "no split expected for key " + k);
            }
            InsertReport r = tree.insertWithReport(5, 5);

            assertEquals(1, r.splits().size());
            InsertReport.Split s = r.splits().get(0);
            assertEquals(InsertReport.NodeKind.LEAF, s.kind());
            assertEquals(originalRoot, s.leftPageID());
            assertEquals(3, s.separatorKey()); // leaf [1,2,3,4] splits at index 2; key 5 lands on the right
            assertTrue(s.createdNewRoot());
            assertEquals(s.parentPageID(), r.rootPageID());
            assertTrue(r.rootChanged());
            assertEquals(originalRoot, r.previousRootPageID());
            assertEquals(originalRoot, r.leafPageID());
            // The new right leaf and the new root are the only pages appended to the file.
            assertEquals(List.of(s.newRightPageID(), s.parentPageID()), r.pagesAllocated());
        }
    }

    @Test
    void internalSplitCascadeToNewRootIsReported() throws IOException {
        try (DiskManager dm = new DiskManager(tempDir.resolve("e.db"))) {
            BPlusTree tree = new BPlusTree(dm, null, 4, 4);
            tree.initialize();

            InsertReport cascade = null;
            for (int k = 1; k <= 200 && cascade == null; k++) {
                InsertReport r = tree.insertWithReport(k, k);
                if (r.splits().size() >= 2) cascade = r;
            }
            assertNotNull(cascade, "an internal split should occur within 200 ascending inserts");
            assertEquals(InsertReport.NodeKind.LEAF, cascade.splits().get(0).kind());
            InsertReport.Split internal = cascade.splits().get(1);
            assertEquals(InsertReport.NodeKind.INTERNAL, internal.kind());
            // Splits are reported child first, then parent; the leaf split's parent is the node that split.
            assertEquals(cascade.splits().get(0).parentPageID(), internal.leftPageID());
            assertFalse(cascade.splits().get(0).createdNewRoot());
        }
    }

    @Test
    void walReportCarriesTransactionDetails() throws IOException {
        try (DiskManager dm = new DiskManager(tempDir.resolve("f.db"));
             WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("f.wal"))) {
            BPlusTree tree = new BPlusTree(dm, wal, 4, 4);
            tree.initialize();

            InsertReport r = tree.insertWithReport(1, 10);
            assertTrue(r.transactionID() > 0);
            assertEquals(PageWriteTransaction.State.APPLIED, r.transactionState());
            assertEquals(List.of(r.leafPageID()), r.pagesWritten());
            assertTrue(r.pagesAllocated().isEmpty());

            // Fill the leaf, then split it: the transaction must have written all three pages.
            for (int k = 2; k <= 4; k++) tree.insert(k, k);
            InsertReport split = tree.insertWithReport(5, 50);
            assertEquals(1, split.splits().size());
            InsertReport.Split s = split.splits().get(0);
            assertTrue(split.pagesWritten().contains(s.leftPageID()));
            assertTrue(split.pagesWritten().contains(s.newRightPageID()));
            assertTrue(split.pagesWritten().contains(s.parentPageID()));
            assertTrue(split.pagesWritten().contains(0L), "root change must rewrite the metadata page");
        }
    }

    @Test
    void insertAndInsertWithReportProduceTheSameTree() throws IOException {
        Random rnd = new Random(7);
        int[] keys = new int[300];
        for (int i = 0; i < keys.length; i++) keys[i] = rnd.nextInt(500);

        List<byte[]> a = new ArrayList<>();
        List<byte[]> b = new ArrayList<>();
        try (DiskManager d1 = new DiskManager(tempDir.resolve("g1.db"));
             DiskManager d2 = new DiskManager(tempDir.resolve("g2.db"))) {
            BPlusTree t1 = new BPlusTree(d1, null, 4, 4);
            BPlusTree t2 = new BPlusTree(d2, null, 4, 4);
            t1.initialize();
            t2.initialize();
            for (int k : keys) {
                t1.insert(k, k * 3);
                t2.insertWithReport(k, k * 3);
            }
            assertEquals(d1.getNumPages(), d2.getNumPages());
            for (long p = 0; p < d1.getNumPages(); p++) {
                assertArrayEquals(d1.readPage(p).getByteBuffer().array(), d2.readPage(p).getByteBuffer().array(),
                        "page " + p + " differs");
            }
        }
    }

    @Test
    void demoCapacityTreeStillMatchesReferenceMapAndReopens() throws IOException {
        Random rnd = new Random(99);
        java.util.Map<Integer, Integer> expected = new java.util.TreeMap<>();
        try (DiskManager dm = new DiskManager(tempDir.resolve("h.db"));
             WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("h.wal"))) {
            BPlusTree tree = new BPlusTree(dm, wal, 4, 4);
            tree.initialize();
            for (int i = 0; i < 400; i++) {
                int k = rnd.nextInt(1000) - 500;
                tree.insert(k, i);
                expected.put(k, i);
            }
        }
        try (DiskManager dm = new DiskManager(tempDir.resolve("h.db"));
             WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("h.wal"))) {
            BPlusTree tree = new BPlusTree(dm, wal, 4, 4);
            tree.initialize();
            for (var e : expected.entrySet()) {
                assertEquals(e.getValue(), tree.search(e.getKey()), "key " + e.getKey());
            }
        }
    }

    @Test
    void readConsistentlyGivesRootAndBlocksNothingForReaders() throws IOException {
        try (DiskManager dm = new DiskManager(tempDir.resolve("i.db"))) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();
            long root = tree.readConsistently((d, r) -> r);
            assertEquals(tree.getRootPageID(), root);
            // Re-entrant read inside a read is allowed (ReentrantReadWriteLock).
            Integer inner = tree.readConsistently((d, r) -> tree.search(1));
            assertNull(inner);
        }
    }
}
