package com.nimeshkmr.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BPlusTreeWalIntegrationTest {

    @TempDir
    Path tempDir;

    private Path databasePath() {
        return tempDir.resolve("database.db");
    }

    private Path walPath() {
        return tempDir.resolve("database.wal");
    }

    @Test
    void insertsSurviveReopeningWithWal() throws IOException {
        try (DiskManager dm = new DiskManager(databasePath());
             WriteAheadLog wal = new WriteAheadLog(walPath())) {

            BPlusTree tree = new BPlusTree(dm, wal);
            tree.initialize();

            tree.insert(10, 100);
            tree.insert(20, 200);
            tree.insert(30, 300);
            tree.insert(20, 999);

            assertEquals(100, tree.search(10));
            assertEquals(999, tree.search(20));
            assertEquals(300, tree.search(30));
        }

        try (DiskManager dm = new DiskManager(databasePath());
             WriteAheadLog wal = new WriteAheadLog(walPath())) {

            BPlusTree tree = new BPlusTree(dm, wal);
            tree.initialize();

            assertEquals(100, tree.search(10));
            assertEquals(999, tree.search(20));
            assertEquals(300, tree.search(30));
            assertNull(tree.search(40));
        }
    }

    @Test
    void leafSplitSurvivesReopeningWithWal() throws IOException {
        try (DiskManager dm = new DiskManager(databasePath());
             WriteAheadLog wal = new WriteAheadLog(walPath())) {

            BPlusTree tree = new BPlusTree(dm, wal);
            tree.initialize();

            for (int key = 0; key < 700; key++) {
                tree.insert(key, key * 10);
            }

            for (int key = 0; key < 700; key++) {
                assertEquals(key * 10, tree.search(key));
            }
        }

        try (DiskManager dm = new DiskManager(databasePath());
             WriteAheadLog wal = new WriteAheadLog(walPath())) {

            BPlusTree tree = new BPlusTree(dm, wal);
            tree.initialize();

            for (int key = 0; key < 700; key++) {
                assertEquals(key * 10, tree.search(key));
            }

            assertNull(tree.search(1000));
        }
    }
}
