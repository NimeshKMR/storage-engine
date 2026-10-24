
package com.nimeshkmr.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WriteAheadLogTest {

    @TempDir
    Path tempDir;

    private Path newWalPath() {
        return tempDir.resolve("test-" + System.nanoTime() + ".wal");
    }

    private Page createTestPage() {
        Page page = new Page();
        ByteBuffer buffer = page.getByteBuffer();

        for (int i = 0; i < 4096; i++) {
            buffer.put(i, (byte) (i % 251));
        }

        return page;
    }

    private void appendRawBytes(Path path, byte[] bytes)
            throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.WRITE)) {

            channel.position(channel.size());
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }

            channel.force(true);
        }
    }

    private void overwriteByte(Path path, long position, byte value)
            throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.WRITE)) {

            ByteBuffer buffer = ByteBuffer.wrap(new byte[]{value});

            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }

            channel.force(true);
        }
    }

    @Test
    void emptyWalStartsTransactionIDsAtOne() throws IOException {
        Path path = newWalPath();

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            assertEquals(1, wal.nextTransactionID());
            assertEquals(2, wal.nextTransactionID());
        }
    }

    @Test
    void pageImageAndCommitRoundTrip() throws IOException {
        Path path = newWalPath();
        Page original = createTestPage();

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            long txID = wal.nextTransactionID();

            wal.appendPageImage(txID, 5, original);
            wal.appendCommit(txID);
            wal.flush();

            LogRecord image = wal.readNextRecord(0);

            assertNotNull(image);
            assertEquals(LogRecord.PAGE_IMAGE, image.getType());
            assertEquals(txID, image.getTransactionID());
            assertEquals(5, image.getPageID());

            byte[] expected = new byte[4096];
            ByteBuffer originalBuffer =
                original.getByteBuffer().duplicate();
            originalBuffer.position(0);
            originalBuffer.get(expected);

            assertArrayEquals(expected, image.getPageImage());

            // Page-image record occupies 4121 bytes.
            LogRecord commit = wal.readNextRecord(4121);

            assertNotNull(commit);
            assertEquals(LogRecord.COMMIT, commit.getType());
            assertEquals(txID, commit.getTransactionID());
            assertEquals(-1, commit.getPageID());
            assertNull(commit.getPageImage());
        }
    }

    @Test
    void scannerReturnsRecordsInOrder() throws IOException {
        Path path = newWalPath();

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            long tx1 = wal.nextTransactionID();
            wal.appendPageImage(tx1, 2, createTestPage());
            wal.appendCommit(tx1);

            long tx2 = wal.nextTransactionID();
            wal.appendPageImage(tx2, 8, createTestPage());
            wal.appendCommit(tx2);

            wal.flush();

            List<LogRecord> records = wal.scanRecords();

            assertEquals(4, records.size());

            assertEquals(LogRecord.PAGE_IMAGE,
                records.get(0).getType());
            assertEquals(tx1, records.get(0).getTransactionID());

            assertEquals(LogRecord.COMMIT,
                records.get(1).getType());
            assertEquals(tx1, records.get(1).getTransactionID());

            assertEquals(LogRecord.PAGE_IMAGE,
                records.get(2).getType());
            assertEquals(tx2, records.get(2).getTransactionID());

            assertEquals(LogRecord.COMMIT,
                records.get(3).getType());
            assertEquals(tx2, records.get(3).getTransactionID());
        }
    }

    @Test
    void transactionIDsContinueAfterReopening() throws IOException {
        Path path = newWalPath();
        long firstID;

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            firstID = wal.nextTransactionID();
            wal.appendPageImage(firstID, 3, createTestPage());
            wal.appendCommit(firstID);
            wal.flush();
        }

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            assertEquals(firstID + 1, wal.nextTransactionID());
        }
    }

    @Test
    void uncommittedTransactionIDIsNotReused()
            throws IOException {
        Path path = newWalPath();
        long uncommittedID;

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            uncommittedID = wal.nextTransactionID();

            // Deliberately do not append a commit record.
            wal.appendPageImage(
                uncommittedID, 4, createTestPage());
            wal.flush();
        }

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            assertEquals(
                uncommittedID + 1,
                wal.nextTransactionID()
            );
        }
    }

    @Test
    void scannerStopsAtIncompleteTrailingPrefix()
            throws IOException {
        Path path = newWalPath();

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            long txID = wal.nextTransactionID();
            wal.appendPageImage(txID, 2, createTestPage());
            wal.appendCommit(txID);
            wal.flush();
        }

        long validSize = Files.size(path);

        // Two bytes cannot form the four-byte length prefix.
        appendRawBytes(path, new byte[]{0x00, 0x00});

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            assertEquals(validSize, Files.size(path));
            assertEquals(2, wal.scanRecords().size());
        }
    }

    @Test
    void reopeningTruncatesIncompleteTailAndAllowsAppend()
            throws IOException {
        Path path = newWalPath();
        long validSize;

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            long txID = wal.nextTransactionID();
            wal.appendPageImage(txID, 2, createTestPage());
            wal.appendCommit(txID);
            wal.flush();
        }

        validSize = Files.size(path);
        appendRawBytes(path, new byte[]{0x00, 0x00});

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            assertEquals(validSize, Files.size(path));

            long newID = wal.nextTransactionID();
            assertEquals(2, newID);

            wal.appendPageImage(newID, 9, createTestPage());
            wal.appendCommit(newID);
            wal.flush();

            List<LogRecord> records = wal.scanRecords();

            assertEquals(4, records.size());
            assertEquals(newID,
                records.get(2).getTransactionID());
            assertEquals(newID,
                records.get(3).getTransactionID());
        }
    }

    @Test
    void rejectsCorruptedPageImageChecksum() throws IOException {
        Path path = newWalPath();

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            wal.appendPageImage(
                wal.nextTransactionID(), 5, createTestPage());
            wal.flush();

            // Payload begins at offset 4.
            // Type + transaction ID + page ID = 17 bytes.
            // Thus the page image starts at offset 21.
            overwriteByte(path, 21, (byte) 0x7F);

            IOException error = assertThrows(
                IOException.class,
                () -> wal.readNextRecord(0)
            );

            assertTrue(error.getMessage().contains("checksum"));
        }
    }

    @Test
    void rejectsInvalidRecordLength() throws IOException {
        Path path = newWalPath();

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            ByteBuffer malformedLength =
                ByteBuffer.allocate(Integer.BYTES);
            malformedLength.putInt(99);

            try (FileChannel channel = FileChannel.open(
                    path, StandardOpenOption.WRITE)) {
                malformedLength.flip();

                while (malformedLength.hasRemaining()) {
                    channel.write(malformedLength, 0);
                }
            }

            IOException error = assertThrows(
                IOException.class,
                () -> wal.readNextRecord(0)
            );

            assertTrue(error.getMessage().contains("length"));
        }
    }

    @Test
    void rejectsIncompleteRecordBody() throws IOException {
        Path path = newWalPath();

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            // Claims a 4117-byte body, but supplies only 10 bytes.
            ByteBuffer prefix = ByteBuffer.allocate(4);
            prefix.putInt(4117);

            try (FileChannel channel = FileChannel.open(
                    path, StandardOpenOption.WRITE)) {
                prefix.flip();

                while (prefix.hasRemaining()) {
                    channel.write(prefix, 0);
                }
            }

            appendRawBytes(path, new byte[10]);

            assertNull(wal.readNextRecord(0));
        }
    }

    @Test
    void rejectsCorruptedCommitChecksum() throws IOException {
        Path path = newWalPath();

        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            long txID = wal.nextTransactionID();
            wal.appendCommit(txID);
            wal.flush();

            // Commit payload begins at offset 4.
            // Change its type byte without updating the checksum.
            overwriteByte(path, 4, (byte) 0x7F);

            IOException error = assertThrows(
                IOException.class,
                () -> wal.readNextRecord(0)
            );

            assertTrue(error.getMessage().contains("checksum"));
        }
    }
}
