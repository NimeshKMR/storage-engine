
package com.nimeshkmr.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class BPlusTreeIntegrationTest {

    private void collectLeafPageIDs(
                        DiskManager diskManager,
                        long pageID,
                        List<Long> leafPageIDs
                ) throws IOException {

                    Page page = diskManager.readPage(pageID);

                    if (page.getByteBuffer().get(0) == 1) {
                        leafPageIDs.add(pageID);
                        return;
                    }

                    InternalNode node = new InternalNode(page);

                    for (int i = 0; i <= node.getNumKeys(); i++) {
                        collectLeafPageIDs(
                                diskManager,
                                node.getChildPageID(i),
                                leafPageIDs
                        );
                    }
                }

    @TempDir
    Path tempDir;

    @Test
    void shouldInsertSearchAndUpdateKeys() throws IOException {
        Path dbPath = tempDir.resolve("basic.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            tree.insert(10, 100);
            tree.insert(20, 200);
            tree.insert(30, 300);

            assertEquals(100, tree.search(10));
            assertEquals(200, tree.search(20));
            assertEquals(300, tree.search(30));
            assertNull(tree.search(40));

            // Updating an existing key must not add a duplicate.
            tree.insert(20, 999);
            assertEquals(999, tree.search(20));
        }
    }

    @Test
    void shouldHandleNegativeAndUnsortedKeys() throws IOException {
        Path dbPath = tempDir.resolve("unsorted.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            int[] keys = {50, -10, 100, 0, 25, -100, 75};

            for (int key : keys) {
                tree.insert(key, key * 3);
            }

            for (int key : keys) {
                assertEquals(key * 3, tree.search(key));
            }

            assertNull(tree.search(999));
        }
    }

    @Test
    void shouldSplitRootLeafAndSearchBothLeaves()
            throws IOException {
        Path dbPath = tempDir.resolve("root-split.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            for (int i = 0; i < 510; i++) {
                tree.insert(i, i * 10);
            }

            long oldRootID = tree.getRootPageID();

            tree.insert(510, 5100);

            assertNotEquals(oldRootID, tree.getRootPageID());

            Page rootPage = dm.readPage(tree.getRootPageID());
            assertEquals(0, rootPage.getByteBuffer().get(0));

            InternalNode root = new InternalNode(rootPage);

            assertEquals(1, root.getNumKeys());
            assertEquals(255, root.getKey(0));

            assertEquals(0, tree.search(0));
            assertEquals(2540, tree.search(254));
            assertEquals(2550, tree.search(255));
            assertEquals(5100, tree.search(510));
            assertNull(tree.search(999));
        }
    }

    @Test
    void shouldSplitNonRootLeaf() throws IOException {
        Path dbPath = tempDir.resolve("non-root-split.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            // First split creates an internal root.
            for (int i = 0; i <= 510; i++) {
                tree.insert(i, i * 10);
            }

            // Fill the right leaf and force another split.
            for (int i = 511; i <= 765; i++) {
                tree.insert(i, i * 10);
            }

            assertEquals(0, tree.search(0));
            assertEquals(2540, tree.search(254));
            assertEquals(2550, tree.search(255));
            assertEquals(5100, tree.search(510));
            assertEquals(7650, tree.search(765));

            InternalNode root =
                new InternalNode(dm.readPage(tree.getRootPageID()));

            assertEquals(2, root.getNumKeys());
        }
    }

    @Test
    void shouldMatchTreeMapForRandomInsertions()
            throws IOException {
        Path dbPath = tempDir.resolve("random.db");

        Random random = new Random(12345);
        Map<Integer, Integer> expected = new TreeMap<>();

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            // Includes duplicates, negative keys and unsorted inserts.
            for (int i = 0; i < 10000; i++) {
                int key = random.nextInt(20000) - 10000;
                int value = random.nextInt();

                tree.insert(key, value);
                expected.put(key, value);
            }

            // Every inserted key must have its latest value.
            for (Map.Entry<Integer, Integer> entry
                    : expected.entrySet()) {
                assertEquals(
                    entry.getValue(),
                    tree.search(entry.getKey()),
                    "Incorrect value for key " + entry.getKey()
                );
            }

            // Check keys absent from the reference map.
            for (int i = 0; i < 1000; i++) {
                int key = 30000 + i;
                assertFalse(expected.containsKey(key));
                assertNull(tree.search(key));
            }
        }
    }

    @Test
    void shouldPersistTreeAfterReopening() throws IOException {
        Path dbPath = tempDir.resolve("reopen.db");
        long savedRootID;

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            for (int i = 0; i <= 510; i++) {
                tree.insert(i, i * 10);
            }

            savedRootID = tree.getRootPageID();
        }

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            assertEquals(savedRootID, tree.getRootPageID());

            assertEquals(0, tree.search(0));
            assertEquals(2540, tree.search(254));
            assertEquals(2550, tree.search(255));
            assertEquals(5100, tree.search(510));
        }
    }

    @Test
    void shouldSplitFullInternalRootAndCreateNewRoot()
            throws IOException {
        Path dbPath = tempDir.resolve("internal-root-split.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            /*
             * With ascending inserts, the first leaf split occurs
             * at key 510. Further right-leaf splits occur every
             * 255 inserted keys.
             *
             * The 341st separator insertion should split the
             * full internal root and create a new root.
             */
            int lastKey = 510 + 340 * 255;

            for (int key = 0; key <= lastKey; key++) {
                tree.insert(key, key * 10);
            }

            Page rootPage = dm.readPage(tree.getRootPageID());
            assertEquals(0, rootPage.getByteBuffer().get(0));

            InternalNode root = new InternalNode(rootPage);

            // The new root should point to two internal nodes.
            assertEquals(1, root.getNumKeys());

            long leftID = root.getChildPageID(0);
            long rightID = root.getChildPageID(1);

            assertNotEquals(leftID, rightID);

            Page leftPage = dm.readPage(leftID);
            Page rightPage = dm.readPage(rightID);

            assertEquals(0, leftPage.getByteBuffer().get(0));
            assertEquals(0, rightPage.getByteBuffer().get(0));

            InternalNode left = new InternalNode(leftPage);
            InternalNode right = new InternalNode(rightPage);

            assertEquals(170, left.getNumKeys());
            assertEquals(170, right.getNumKeys());

            // Check keys on both sides of the new root separator.
            int separator = root.getKey(0);

            assertEquals(separator * 10, tree.search(separator));
            assertEquals((separator - 1) * 10,
                         tree.search(separator - 1));
            assertEquals((separator + 1) * 10,
                         tree.search(separator + 1));

            assertEquals(0, tree.search(0));
            assertEquals(lastKey * 10, tree.search(lastKey));
        }
    }

    @Test
    void shouldPersistMultiLevelTreeAfterReopening()
            throws IOException {
        Path dbPath = tempDir.resolve("multi-level-reopen.db");

        int lastKey = 510 + 340 * 255;
        long savedRootID;

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            for (int key = 0; key <= lastKey; key++) {
                tree.insert(key, key * 10);
            }

            savedRootID = tree.getRootPageID();
        }

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            assertEquals(savedRootID, tree.getRootPageID());

            assertEquals(0, tree.search(0));
            assertEquals(2550, tree.search(255));
            assertEquals(100000, tree.search(10000));
            assertEquals(lastKey * 10, tree.search(lastKey));
        }
    }

    @Test
    void shouldMaintainCorrectLeafChainAfterMultipleSplits() throws IOException {
        Path dbPath = tempDir.resolve("leaf-chain-test.db");

        try (DiskManager diskManager = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(diskManager);
            tree.initialize();

            int numberOfKeys = 2_000;

            // Insert keys in ascending order to trigger multiple leaf splits.
            for (int key = 0; key < numberOfKeys; key++) {
                tree.insert(key, key * 10);
            }

            // Collect leaves by traversing the tree structure.
            List<Long> treeLeafIDs = new ArrayList<>();

            collectLeafPageIDs(
                    diskManager,
                    tree.getRootPageID(),
                    treeLeafIDs
            );

            assertTrue(treeLeafIDs.size() > 2,
                    "The test should create multiple leaf pages");

            // Traverse the linked list starting from the leftmost leaf.
            long currentPageID = treeLeafIDs.get(0);
            List<Integer> actualKeys = new ArrayList<>();
            List<Long> chainLeafIDs = new ArrayList<>();

            while (currentPageID != -1) {
                assertFalse(
                        chainLeafIDs.contains(currentPageID),
                        "Leaf chain contains a cycle at page " + currentPageID
                );

                chainLeafIDs.add(currentPageID);

                Page page = diskManager.readPage(currentPageID);
                LeafNode leaf = new LeafNode(page);

                for (int i = 0; i < leaf.getNumKeys(); i++) {
                    actualKeys.add(leaf.getKey(i));
                }

                currentPageID = leaf.getNextLeafPageID();
            }

            // The linked list must contain exactly the leaves in tree order.
            assertEquals(
                    treeLeafIDs,
                    chainLeafIDs,
                    "Leaf chain does not match the tree's leaf order"
            );

            // Every inserted key must appear once, in strictly increasing order.
            assertEquals(numberOfKeys, actualKeys.size());

            for (int i = 0; i < numberOfKeys; i++) {
                assertEquals(
                        i,
                        actualKeys.get(i),
                        "Missing, duplicated, or out-of-order key at index " + i
                );
            }

            // Confirm the last leaf terminates the chain.
            Page lastPage = diskManager.readPage(
                    chainLeafIDs.get(chainLeafIDs.size() - 1)
            );
            LeafNode lastLeaf = new LeafNode(lastPage);

            assertEquals(-1L, lastLeaf.getNextLeafPageID());
        }
    }

    @Test
    void shouldPersistLeafChainAfterReopening() throws IOException {
        Path dbPath = tempDir.resolve("leaf-chain-persistence-test.db");
        int numberOfKeys = 2_000;

        // First session: create the tree and insert keys.
        try (DiskManager diskManager = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(diskManager);
            tree.initialize();

            for (int key = 0; key < numberOfKeys; key++) {
                tree.insert(key, key * 10);
            }
        }

        // Second session: reopen the database and validate persisted structure.
        try (DiskManager diskManager = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(diskManager);
            tree.initialize();

            List<Long> treeLeafIDs = new ArrayList<>();

            collectLeafPageIDs(
                    diskManager,
                    tree.getRootPageID(),
                    treeLeafIDs
            );

            assertTrue(
                    treeLeafIDs.size() > 2,
                    "The reopened tree should contain multiple leaf pages"
            );

            List<Long> chainLeafIDs = new ArrayList<>();
            List<Integer> actualKeys = new ArrayList<>();

            long currentPageID = treeLeafIDs.get(0);

            while (currentPageID != -1L) {
                assertFalse(
                        chainLeafIDs.contains(currentPageID),
                        "Leaf chain contains a cycle at page " + currentPageID
                );

                chainLeafIDs.add(currentPageID);

                Page page = diskManager.readPage(currentPageID);
                LeafNode leaf = new LeafNode(page);

                for (int i = 0; i < leaf.getNumKeys(); i++) {
                    actualKeys.add(leaf.getKey(i));

                    assertEquals(
                            leaf.getKey(i) * 10,
                            tree.search(leaf.getKey(i)),
                            "Incorrect value for key " + leaf.getKey(i)
                    );
                }

                currentPageID = leaf.getNextLeafPageID();
            }

            // Tree traversal and linked-list traversal must agree.
            assertEquals(
                    treeLeafIDs,
                    chainLeafIDs,
                    "Persisted leaf chain differs from tree structure"
            );

            // Every key must appear exactly once in sorted order.
            assertEquals(numberOfKeys, actualKeys.size());

            for (int i = 0; i < numberOfKeys; i++) {
                assertEquals(
                        i,
                        actualKeys.get(i),
                        "Missing, duplicated, or out-of-order key at index " + i
                );
            }

            // The final leaf must terminate the chain.
            Page lastPage = diskManager.readPage(
                    chainLeafIDs.get(chainLeafIDs.size() - 1)
            );

            LeafNode lastLeaf = new LeafNode(lastPage);

            assertEquals(-1L, lastLeaf.getNextLeafPageID());
        }
    }
}
