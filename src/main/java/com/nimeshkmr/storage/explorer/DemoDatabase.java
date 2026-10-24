package com.nimeshkmr.storage.explorer;

import com.nimeshkmr.storage.BPlusTree;
import com.nimeshkmr.storage.DiskManager;
import com.nimeshkmr.storage.WriteAheadLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One open demo database: the real DiskManager, WriteAheadLog and BPlusTree for one set of files.
 *
 * <p>Operations run under a lifecycle read lock so that close() waits for requests in flight and
 * later requests see a clear NOT_OPEN error instead of a closed channel.
 */
final class DemoDatabase {

    @FunctionalInterface
    interface Action<T> {
        T run(DemoDatabase db) throws IOException;
    }

    final String name;
    final Path dbPath;
    final Path walPath;
    final int leafCapacity;
    final int internalCapacity;
    final DiskManager diskManager;
    final WriteAheadLog wal;
    final BPlusTree tree;

    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;

    private DemoDatabase(String name, Path dbPath, Path walPath, int leafCapacity, int internalCapacity,
            DiskManager dm, WriteAheadLog wal, BPlusTree tree) {
        this.name = name;
        this.dbPath = dbPath;
        this.walPath = walPath;
        this.leafCapacity = leafCapacity;
        this.internalCapacity = internalCapacity;
        this.diskManager = dm;
        this.wal = wal;
        this.tree = tree;
    }

    /**
     * Opens the files and runs BPlusTree#initialize(), which runs the engine's recovery on the WAL
     * and creates the tree if the file is empty. Resources are released if anything fails.
     */
    static DemoDatabase open(String name, Path dbPath, Path walPath, int leafCapacity, int internalCapacity)
            throws IOException {
        DiskManager dm = null;
        WriteAheadLog wal = null;
        try {
            dm = new DiskManager(dbPath);
            wal = new WriteAheadLog(walPath);
            BPlusTree tree = new BPlusTree(dm, wal, leafCapacity, internalCapacity);
            tree.initialize();
            return new DemoDatabase(name, dbPath, walPath, leafCapacity, internalCapacity, dm, wal, tree);
        } catch (IOException | RuntimeException e) {
            closeQuietly(wal, e);
            closeQuietly(dm, e);
            throw e;
        }
    }

    private static void closeQuietly(AutoCloseable c, Throwable primary) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure); // surfaced with the original failure, not swallowed
        }
    }

    <T> T use(Action<T> action) throws IOException {
        lifecycle.readLock().lock();
        try {
            if (closed) {
                throw ApiException.conflict("NOT_OPEN", "Database '" + name + "' is not open");
            }
            try {
                return action.run(this);
            } catch (IllegalStateException e) {
                if (tree.isRecoveryRequired()) {
                    throw new ApiException(409, "RECOVERY_REQUIRED",
                            "A commit failed part-way; close and reopen the database to run recovery. "
                                    + "Engine message: " + e.getMessage(), e);
                }
                throw new ApiException(409, "ENGINE_STATE", e.getMessage(), e);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    boolean isClosed() {
        lifecycle.readLock().lock();
        try {
            return closed;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Waits for in-flight requests, then closes the WAL and database file. Idempotent. */
    void close() throws IOException {
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                wal.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                diskManager.close();
            } catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
}
