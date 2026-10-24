
package com.nimeshkmr.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BPlusTreeConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentInsertsPreserveAllEntries() throws Exception {
        Path dbPath = tempDir.resolve("concurrent.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            int threadCount = 4;
            int keysPerThread = 500;

            ExecutorService executor =
                    Executors.newFixedThreadPool(threadCount);

            try {
                Future<?>[] futures = new Future<?>[threadCount];

                for (int t = 0; t < threadCount; t++) {
                    final int threadId = t;

                    futures[t] = executor.submit(() -> {
                        for (int i = 0; i < keysPerThread; i++) {
                            int key = threadId * keysPerThread + i;
                            try {
                                tree.insert(key, key * 10);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                }

                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }

                for (int key = 0;
                     key < threadCount * keysPerThread; key++) {
                    assertEquals(key * 10, tree.search(key),
                            "Incorrect or missing key: " + key);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    
    @Test
    void concurrentReadsAndWritesPreserveTreeConsistency() throws Exception {
        Path dbPath = tempDir.resolve("mixed-concurrency.db");

        try (DiskManager dm = new DiskManager(dbPath)) {
            BPlusTree tree = new BPlusTree(dm);
            tree.initialize();

            // Preload keys that readers can safely search throughout the test.
            int stableKeys = 100;
            for (int key = 0; key < stableKeys; key++) {
                tree.insert(key, key * 10);
            }

            int writerCount = 4;
            int keysPerWriter = 250;
            int readerCount = 2;

            ExecutorService executor =
                    Executors.newFixedThreadPool(writerCount + readerCount);
            CountDownLatch start = new CountDownLatch(1);

            try {
                List<Future<?>> futures = new ArrayList<>();

                // Writers insert disjoint key ranges.
                for (int t = 0; t < writerCount; t++) {
                    final int threadId = t;

                    futures.add(executor.submit(() -> {
                        start.await();

                        for (int i = 0; i < keysPerWriter; i++) {
                            int key = stableKeys
                                    + threadId * keysPerWriter + i;
                            tree.insert(key, key * 10);
                        }
                        return null;
                    }));
                }

                // Readers repeatedly search keys that already exist.
                for (int t = 0; t < readerCount; t++) {
                    final int readerId = t;

                    futures.add(executor.submit(() -> {
                        start.await();

                        for (int i = 0; i < 1000; i++) {
                            int key = (i + readerId) % stableKeys;
                            assertEquals(key * 10, tree.search(key));
                        }
                        return null;
                    }));
                }

                start.countDown();

                for (Future<?> future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }

                // Verify every new entry after all workers have completed.
                for (int key = stableKeys;
                    key < stableKeys + writerCount * keysPerWriter; key++) {
                    assertEquals(key * 10, tree.search(key),
                            "Missing or incorrect key: " + key);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    
    @Test
    void concurrentWalInsertsSurviveReopen() throws Exception {
        Path dbPath = tempDir.resolve("wal-concurrency.db");
        Path walPath = tempDir.resolve("wal-concurrency.log");

        int threadCount = 4;
        int keysPerThread = 250;

        try (DiskManager dm = new DiskManager(dbPath);
            WriteAheadLog wal = new WriteAheadLog(walPath)) {

            BPlusTree tree = new BPlusTree(dm, wal);
            tree.initialize();

            ExecutorService executor =
                    Executors.newFixedThreadPool(threadCount);

            try {
                Future<?>[] futures = new Future<?>[threadCount];

                for (int t = 0; t < threadCount; t++) {
                    final int threadId = t;

                    futures[t] = executor.submit(() -> {
                        for (int i = 0; i < keysPerThread; i++) {
                            int key = threadId * keysPerThread + i;
                            try {
                                tree.insert(key, key * 10);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                }

                for (Future<?> future : futures) {
                    future.get(120, TimeUnit.SECONDS);
                }

                for (int key = 0; key < threadCount * keysPerThread; key++) {
                    assertEquals(key * 10, tree.search(key),
                            "Incorrect value before reopen: " + key);
                }
            } finally {
                executor.shutdownNow();
            }
        }

        // Reopen both files and initialize the tree, triggering recovery.
        try (DiskManager dm = new DiskManager(dbPath);
            WriteAheadLog wal = new WriteAheadLog(walPath)) {

            BPlusTree reopenedTree = new BPlusTree(dm, wal);
            reopenedTree.initialize();

            for (int key = 0; key < threadCount * keysPerThread; key++) {
                assertEquals(key * 10, reopenedTree.search(key),
                        "Incorrect value after reopen: " + key);
            }
        }
    }


}
