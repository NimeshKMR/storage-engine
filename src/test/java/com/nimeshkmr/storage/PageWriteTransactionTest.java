package com.nimeshkmr.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PageWriteTransactionTest {

    @TempDir
    Path tempDir;

    private Page createPage(byte value) {
        Page page = new Page();
        ByteBuffer buffer = page.getByteBuffer();

        for (int i = 0; i < Page.PAGE_SIZE; i++) {
            buffer.put(i, value);
        }

        return page;
    }

    @Test
    void successfulCommitEndsInAppliedState() throws IOException {
        try (DiskManager disk =
                    new DiskManager(tempDir.resolve("database.db"));
             WriteAheadLog wal =
                    new WriteAheadLog(tempDir.resolve("database.wal"))) {

            long pageID = disk.allocatePage();

            PageWriteTransaction tx = new PageWriteTransaction(
                wal.nextTransactionID(), wal, disk
            );

            tx.writePage(pageID, createPage((byte) 7));
            assertEquals(PageWriteTransaction.State.ACTIVE, tx.getState());

            tx.commit();

            assertEquals(PageWriteTransaction.State.APPLIED, tx.getState());
            assertTrue(tx.isCommittedOrUncertain());
            assertThrows(IllegalStateException.class, tx::abort);
        }
    }

    @Test
    void abortBeforeCommitEndsInAbortedState() throws IOException {
        try (DiskManager disk =
                    new DiskManager(tempDir.resolve("database.db"));
             WriteAheadLog wal =
                    new WriteAheadLog(tempDir.resolve("database.wal"))) {

            long pageID = disk.allocatePage();

            PageWriteTransaction tx = new PageWriteTransaction(
                wal.nextTransactionID(), wal, disk
            );

            tx.writePage(pageID, createPage((byte) 7));
            tx.abort();

            assertEquals(PageWriteTransaction.State.ABORTED, tx.getState());
            assertFalse(tx.isCommittedOrUncertain());
            assertThrows(IllegalStateException.class, tx::commit);
        }
    }
}
