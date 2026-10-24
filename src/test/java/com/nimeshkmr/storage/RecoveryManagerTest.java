
package com.nimeshkmr.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryManagerTest {

    @TempDir
    Path tempDir;

    private Path databasePath() {
        return tempDir.resolve("database.db");
    }

    private Path walPath() {
        return tempDir.resolve("database.wal");
    }

    private Page createPage(byte seed) {
        Page page = new Page();
        ByteBuffer buffer = page.getByteBuffer();

        for (int i = 0; i < Page.PAGE_SIZE; i++) {
            buffer.put(i, (byte) (seed + i));
        }

        return page;
    }

    private byte[] bytesOf(Page page) {
        ByteBuffer buffer = page.getByteBuffer().duplicate();
        buffer.clear();

        byte[] bytes = new byte[Page.PAGE_SIZE];
        buffer.get(bytes);
        return bytes;
    }

    private void assertPageEquals(Page expected, Page actual) {
        assertArrayEquals(bytesOf(expected), bytesOf(actual));
    }

    @Test
    void committedTransactionIsReplayed() throws IOException {
        long pageID;

        Page original = createPage((byte) 10);
        Page committedImage = createPage((byte) 50);

        try (DiskManager disk = new DiskManager(databasePath())) {
            pageID = disk.allocatePage();
            disk.writePage(pageID, original);

            try (WriteAheadLog wal = new WriteAheadLog(walPath())) {
                long txID = wal.nextTransactionID();
                wal.appendPageImage(txID, pageID, committedImage);
                wal.flush();
                wal.appendCommit(txID);
                wal.flush();

                new RecoveryManager().recover(wal, disk);

                assertPageEquals(committedImage, disk.readPage(pageID));
            }
        }
    }

    @Test
    void uncommittedTransactionIsIgnored() throws IOException {
        long pageID;

        Page original = createPage((byte) 10);
        Page uncommittedImage = createPage((byte) 90);

        try (DiskManager disk = new DiskManager(databasePath())) {
            pageID = disk.allocatePage();
            disk.writePage(pageID, original);

            try (WriteAheadLog wal = new WriteAheadLog(walPath())) {
                long txID = wal.nextTransactionID();
                wal.appendPageImage(txID, pageID, uncommittedImage);
                wal.flush();

                new RecoveryManager().recover(wal, disk);

                assertPageEquals(original, disk.readPage(pageID));
            }
        }
    }

    @Test
    void committedTransactionIsReplayedButUncommittedLaterImageIsIgnored()
            throws IOException {

        long pageID;
        Page committedImage = createPage((byte) 40);
        Page uncommittedImage = createPage((byte) 80);

        try (DiskManager disk = new DiskManager(databasePath())) {
            pageID = disk.allocatePage();
            disk.writePage(pageID, createPage((byte) 1));

            try (WriteAheadLog wal = new WriteAheadLog(walPath())) {
                long committedTx = wal.nextTransactionID();
                wal.appendPageImage(committedTx, pageID, committedImage);
                wal.flush();
                wal.appendCommit(committedTx);
                wal.flush();

                long uncommittedTx = wal.nextTransactionID();
                wal.appendPageImage(uncommittedTx, pageID, uncommittedImage);
                wal.flush();

                new RecoveryManager().recover(wal, disk);

                assertPageEquals(committedImage, disk.readPage(pageID));
            }
        }
    }

    @Test
    void recoveryIsIdempotent() throws IOException {
        long pageID;
        Page committedImage = createPage((byte) 42);

        try (DiskManager disk = new DiskManager(databasePath())) {
            pageID = disk.allocatePage();
            disk.writePage(pageID, createPage((byte) 1));

            try (WriteAheadLog wal = new WriteAheadLog(walPath())) {
                long txID = wal.nextTransactionID();
                wal.appendPageImage(txID, pageID, committedImage);
                wal.flush();
                wal.appendCommit(txID);
                wal.flush();

                RecoveryManager recovery = new RecoveryManager();

                recovery.recover(wal, disk);
                Page afterFirstRecovery = disk.readPage(pageID);

                recovery.recover(wal, disk);
                Page afterSecondRecovery = disk.readPage(pageID);

                assertPageEquals(committedImage, afterFirstRecovery);
                assertPageEquals(afterFirstRecovery, afterSecondRecovery);
            }
        }
    }

    @Test
    void recoveryReplaysMultiplePagesFromCommittedTransaction()
            throws IOException {

        long firstPageID;
        long secondPageID;

        Page firstImage = createPage((byte) 20);
        Page secondImage = createPage((byte) 60);

        try (DiskManager disk = new DiskManager(databasePath())) {
            firstPageID = disk.allocatePage();
            secondPageID = disk.allocatePage();

            disk.writePage(firstPageID, createPage((byte) 1));
            disk.writePage(secondPageID, createPage((byte) 2));

            try (WriteAheadLog wal = new WriteAheadLog(walPath())) {
                long txID = wal.nextTransactionID();

                wal.appendPageImage(txID, firstPageID, firstImage);
                wal.appendPageImage(txID, secondPageID, secondImage);
                wal.flush();

                wal.appendCommit(txID);
                wal.flush();

                new RecoveryManager().recover(wal, disk);

                assertPageEquals(firstImage, disk.readPage(firstPageID));
                assertPageEquals(secondImage, disk.readPage(secondPageID));
            }
        }
    }

    @Test
    void recoveredPagesPersistAfterDatabaseReopens() throws IOException {
        long pageID;
        Page committedImage = createPage((byte) 77);

        try (DiskManager disk = new DiskManager(databasePath())) {
            pageID = disk.allocatePage();
            disk.writePage(pageID, createPage((byte) 1));

            try (WriteAheadLog wal = new WriteAheadLog(walPath())) {
                long txID = wal.nextTransactionID();
                wal.appendPageImage(txID, pageID, committedImage);
                wal.flush();
                wal.appendCommit(txID);
                wal.flush();

                new RecoveryManager().recover(wal, disk);
            }
        }

        try (DiskManager reopenedDisk = new DiskManager(databasePath())) {
            assertPageEquals(
                    committedImage,
                    reopenedDisk.readPage(pageID)
            );
        }
    }

    
    @Test
    void corruptWalIsRejectedWhenOpened() throws IOException {
        long pageID;

        try (DiskManager disk = new DiskManager(databasePath())) {
            pageID = disk.allocatePage();
            disk.writePage(pageID, createPage((byte) 1));

            try (WriteAheadLog wal = new WriteAheadLog(walPath())) {
                long txID = wal.nextTransactionID();
                wal.appendPageImage(txID, pageID, createPage((byte) 9));
                wal.flush();
                wal.appendCommit(txID);
                wal.flush();
            }

            // Corrupt the first byte of the page image without updating
            // its checksum.
            try (var channel = java.nio.channels.FileChannel.open(
                    walPath(),
                    java.nio.file.StandardOpenOption.WRITE)) {

                ByteBuffer changedByte = ByteBuffer.wrap(new byte[]{99});
                channel.write(changedByte, 21);
            }

            // The WAL constructor should reject the invalid checksum.
            assertThrows(
                    IOException.class,
                    () -> new WriteAheadLog(walPath())
            );

            // The corrupt log must not have changed the database page.
            assertPageEquals(
                    createPage((byte) 1),
                    disk.readPage(pageID)
            );
        }
    }

    
    @Test
    void recoveryExtendsDatabaseForMissingCommittedPage()
            throws IOException {

        Page committedImage = createPage((byte) 55);
        long missingPageID = 2;

        try (DiskManager disk = new DiskManager(databasePath())) {
            // Only page 0 exists.
            disk.allocatePage();

            try (WriteAheadLog wal = new WriteAheadLog(walPath())) {
                long txID = wal.nextTransactionID();

                wal.appendPageImage(txID, missingPageID, committedImage);
                wal.flush();

                wal.appendCommit(txID);
                wal.flush();

                new RecoveryManager().recover(wal, disk);

                assertEquals(3, disk.getNumPages());
                assertPageEquals(
                    committedImage,
                    disk.readPage(missingPageID)
                );
            }
        }
    }

    
private static class FailOnSecondRestoreDiskManager extends DiskManager {

    private int restoreCount = 0;

    FailOnSecondRestoreDiskManager(Path path) throws IOException {
        super(path);
    }

    @Override
    public void restorePage(long pageID, Page page) throws IOException {
        restoreCount++;

        if (restoreCount == 2) {
            throw new IOException("Simulated database write failure");
        }

        super.restorePage(pageID, page);
    }
}

    @Test
    void recoveryCompletesCommittedTransactionAfterPartialDatabaseWrite()
            throws IOException {

        long firstPageID;
        long secondPageID;

        Page originalFirst = createPage((byte) 1);
        Page originalSecond = createPage((byte) 2);
        Page committedFirst = createPage((byte) 40);
        Page committedSecond = createPage((byte) 80);

        // Prepare the original database with two allocated pages.
        try (DiskManager disk = new DiskManager(databasePath())) {
            firstPageID = disk.allocatePage();
            secondPageID = disk.allocatePage();

            disk.writePage(firstPageID, originalFirst);
            disk.writePage(secondPageID, originalSecond);
            disk.force();
        }

        // Simulate a failure after the commit is durable and the first
        // database page has been applied.
        try (FailOnSecondRestoreDiskManager disk =
                    new FailOnSecondRestoreDiskManager(databasePath());
            WriteAheadLog wal = new WriteAheadLog(walPath())) {

            PageWriteTransaction tx = new PageWriteTransaction(
                wal.nextTransactionID(), wal, disk
            );

            tx.writePage(firstPageID, committedFirst);
            tx.writePage(secondPageID, committedSecond);

            assertThrows(IOException.class, tx::commit);

            // Page 1 was applied; page 2 should still contain old data.
            assertPageEquals(committedFirst, disk.readPage(firstPageID));
            assertPageEquals(originalSecond, disk.readPage(secondPageID));

            // Do NOT treat this as a rollback: the commit record is durable.
        }

        // Reopen the real database and WAL, then recover.
        try (DiskManager disk = new DiskManager(databasePath());
            WriteAheadLog wal = new WriteAheadLog(walPath())) {

            new RecoveryManager().recover(wal, disk);

            assertPageEquals(committedFirst, disk.readPage(firstPageID));
            assertPageEquals(committedSecond, disk.readPage(secondPageID));
        }
    }



}
