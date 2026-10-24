
package com.nimeshkmr.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PageWriteTransaction {

    public enum State {
        ACTIVE,
        COMMIT_UNCERTAIN,
        COMMITTED,
        APPLIED,
        ABORTED
    }

    private final long transactionID;
    private final WriteAheadLog wal;
    private final DiskManager diskManager;

    private final Map<Long, Page> dirtyPages = new LinkedHashMap<>();

    private State state = State.ACTIVE;

    public PageWriteTransaction(
            long transactionID,
            WriteAheadLog wal,
            DiskManager diskManager) {

        if (transactionID <= 0) {
            throw new IllegalArgumentException(
                "Transaction ID must be positive"
            );
        }

        this.transactionID = transactionID;
        this.wal = wal;
        this.diskManager = diskManager;
    }

    public State getState() {
        return state;
    }

    public long getTransactionID() {
        return transactionID;
    }

    /** Page IDs staged by this transaction, in write order (empty after abort). */
    public List<Long> getStagedPageIDs() {
        return new ArrayList<>(dirtyPages.keySet());
    }

    public boolean isCommittedOrUncertain() {
        return state == State.COMMIT_UNCERTAIN
            || state == State.COMMITTED
            || state == State.APPLIED;
    }

    public Page readPage(long pageID) throws IOException {
        ensureActive();

        Page staged = dirtyPages.get(pageID);
        if (staged != null) {
            return copyPage(staged);
        }

        return diskManager.readPage(pageID);
    }

    public void writePage(long pageID, Page page) {
        ensureActive();

        if (pageID < 0) {
            throw new IllegalArgumentException("Invalid page ID");
        }

        dirtyPages.put(pageID, copyPage(page));
    }

    public long allocatePage() throws IOException {
        ensureActive();
        return diskManager.allocatePage();
    }

    public void commit() throws IOException {
        ensureActive();

        // Phase 1: log every staged page image.
        for (Map.Entry<Long, Page> entry : dirtyPages.entrySet()) {
            wal.appendPageImage(
                transactionID,
                entry.getKey(),
                entry.getValue()
            );
        }

        // Page images must be durable before the commit record.
        wal.flush();

        // From this point, a commit attempt may leave a valid commit
        // record in the WAL even if an IOException is thrown.
        state = State.COMMIT_UNCERTAIN;

        wal.appendCommit(transactionID);
        wal.flush();

        // A successful flush confirms the commit according to our protocol.
        state = State.COMMITTED;

        // Apply committed page images to the database.
        for (Map.Entry<Long, Page> entry : dirtyPages.entrySet()) {
            diskManager.restorePage(
                entry.getKey(),
                copyPage(entry.getValue())
            );
        }

        diskManager.force();

        state = State.APPLIED;
    }

    public void abort() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException(
                "Cannot abort transaction in state " + state
            );
        }

        dirtyPages.clear();
        state = State.ABORTED;
    }

    private Page copyPage(Page source) {
        Page copy = new Page();

        ByteBuffer src = source.getByteBuffer().duplicate();
        src.position(0);
        src.limit(Page.PAGE_SIZE);

        copy.getByteBuffer().put(src);
        return copy;
    }

    private void ensureActive() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException(
                "Transaction is not active: " + state
            );
        }
    }
}
