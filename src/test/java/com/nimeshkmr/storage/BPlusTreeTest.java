
package com.nimeshkmr.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BPlusTreeTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeNewTree() throws IOException {
        Path dbPath = tempDir.resolve("new_database.db");

        try (DiskManager dm = new DiskManager()) {
            // This test currently uses DiskManager's default database.db path.
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            assertEquals(2, dm.getNumPages());
            assertEquals(1, tree.getRootPageID());
        }
    }

    @Test
    void reopeningTreePreservesRootPageID() throws IOException {
        long originalRootPageID;

        try (DiskManager dm = new DiskManager()) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            originalRootPageID = tree.getRootPageID();
            assertEquals(2, dm.getNumPages());
        }

        try (DiskManager dm = new DiskManager()) {
            BPlusTree reopenedTree = new BPlusTree(dm);
            reopenedTree.initialize();

            assertEquals(originalRootPageID, reopenedTree.getRootPageID());
            assertEquals(2, dm.getNumPages());
        }
    }
    
    @Test
    void insertAndSearchValues() throws IOException {
        Path dbPath = tempDir.resolve("insert_search.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            tree.insert(30, 300);
            tree.insert(10, 100);
            tree.insert(20, 200);

            assertEquals(100, tree.search(10));
            assertEquals(200, tree.search(20));
            assertEquals(300, tree.search(30));
            assertNull(tree.search(40));
        }
    }

    @Test
    void insertingExistingKeyUpdatesValue() throws IOException {
        Path dbPath = tempDir.resolve("update_value.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            tree.insert(10, 100);
            tree.insert(10, 999);

            assertEquals(999, tree.search(10));

            Page rootPage = dm.readPage(tree.getRootPageID());
            LeafNode leaf = new LeafNode(rootPage);

            assertEquals(1, leaf.getNumKeys());
        }
    }

    @Test
    void insertedValuesSurviveReopeningDatabase() throws IOException {
        Path dbPath = tempDir.resolve("persistence.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            tree.insert(10, 100);
            tree.insert(20, 200);
        }

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            assertEquals(100, tree.search(10));
            assertEquals(200, tree.search(20));
            assertNull(tree.search(30));
        }
    }

    @Test
    void shouldSplitRootLeafAndCreateNewRoot() throws IOException {
        Path dbPath = tempDir.resolve("test.db");

        try (DiskManager diskManager = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(diskManager);
            tree.initialize();

            // Fill the original root leaf with 510 entries.
            for (int i = 0; i < 510; i++) {
                tree.insert(i, i * 10);
            }

            long oldRootPageID = tree.getRootPageID();

            // The 511th distinct key should trigger a split.
            tree.insert(510, 5100);

            long newRootPageID = tree.getRootPageID();

            // A new internal root should have been created.
            assertNotEquals(oldRootPageID, newRootPageID);

            // The new root should be an internal node (type byte = 0).
            Page rootPage = diskManager.readPage(newRootPageID);
            assertEquals(0, rootPage.getByteBuffer().get(0));

            // The new root should have one separator key.
            InternalNode root = new InternalNode(rootPage);
            assertEquals(1, root.getNumKeys());

            // The separator should be the first key in the right leaf.
            assertEquals(255, root.getKey(0));

            // The root should point to two different child pages.
            long leftChild = root.getChildPageID(0);
            long rightChild = root.getChildPageID(1);

            assertEquals(oldRootPageID, leftChild);
            assertNotEquals(leftChild, rightChild);

            // Both leaves should be searchable.
            assertEquals(0, tree.search(0));
            assertEquals(2540, tree.search(254));
            assertEquals(2550, tree.search(255));
            assertEquals(5090, tree.search(509));
            assertEquals(5100, tree.search(510));

            // A key that was never inserted should not be found.
            assertNull(tree.search(999));
        }
    }

    @Test
    void shouldPersistNewRootAfterReopeningDatabase() throws IOException {
        Path dbPath = tempDir.resolve("persistent-test.db");
        long savedRootPageID;

        // Create the tree and trigger the root split.
        try (DiskManager diskManager = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(diskManager);
            tree.initialize();

            for (int i = 0; i < 510; i++) {
                tree.insert(i, i * 10);
            }

            tree.insert(510, 5100);
            savedRootPageID = tree.getRootPageID();
        }

        // Reopen the same database file.
        try (DiskManager diskManager = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(diskManager);
            tree.initialize();

            // The root ID should have survived reopening.
            assertEquals(savedRootPageID, tree.getRootPageID());

            // Search should traverse the persisted internal root.
            assertEquals(0, tree.search(0));
            assertEquals(2500, tree.search(250));
            assertEquals(2550, tree.search(255));
            assertEquals(5000, tree.search(500));
            assertEquals(5100, tree.search(510));

            assertNull(tree.search(999));
        }
    }

    @Test
    void shouldSplitNonRootLeaf() throws IOException {
        Path dbPath = tempDir.resolve("non-root-split.db");

        try (DiskManager diskManager = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(diskManager);
            tree.initialize();

            // Fill the original root leaf.
            for (int i = 0; i < 510; i++) {
                tree.insert(i, i * 10);
            }

            // Split the root leaf.
            tree.insert(510, 5100);

            // Fill the right leaf to 510 entries.
            for (int i = 511; i <= 764; i++) {
                tree.insert(i, i * 10);
            }

            // This should split the full right leaf.
            tree.insert(765, 7650);

            assertEquals(0, tree.search(0));
            assertEquals(2540, tree.search(254));
            assertEquals(2550, tree.search(255));
            assertEquals(5000, tree.search(500));
            assertEquals(7650, tree.search(765));

            Page rootPage = diskManager.readPage(tree.getRootPageID());
            InternalNode root = new InternalNode(rootPage);

            // The root now needs two separator keys.
            assertEquals(2, root.getNumKeys());
        }
    }

}
