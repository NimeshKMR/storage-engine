package com.nimeshkmr.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;


public class WriteAheadLog implements AutoCloseable{
    private static final byte PAGE_IMAGE = 1;
    private static final byte COMMIT = 2;
    private static final int PAGE_SIZE = 4096;

    private final FileChannel channel;
    private long nextTransactionID = 1;

    
    public WriteAheadLog(Path path) throws IOException {
        channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );

        long fileSize = channel.size();
        long position = 0;
        long maxTransactionID = 0;

        while (position < fileSize) {
            LogRecord record = readNextRecord(position);
            if (record == null) {
                // Remove only the incomplete trailing record.
                channel.truncate(position);
                break;
            }
            maxTransactionID = Math.max(
                maxTransactionID,
                record.getTransactionID()
            );
            ByteBuffer lengthBuffer =
                readRecord(position, Integer.BYTES);

            int recordLength = lengthBuffer.getInt();
            position += Integer.BYTES + recordLength;
        }
        if (maxTransactionID == Long.MAX_VALUE) {
            throw new IOException("WAL transaction ID space exhausted");
        }
        nextTransactionID = maxTransactionID + 1;
        // Position future writes at the actual end of the WAL.
        channel.position(channel.size());
    }


    public long getLogSize() throws IOException{
        return channel.size();
    }

    
    public void appendPageImage(long transactionID, long pageID, Page page)
            throws IOException {
        if(transactionID <= 0){
            throw new IllegalArgumentException("Invalid transaction ID!");
        }
        if(pageID < 0){
            throw new IllegalArgumentException("Invalid page ID");
        }
        ByteBuffer image = page.getByteBuffer().duplicate();
        image.position(0);
        image.limit(PAGE_SIZE);
        ByteBuffer payload = ByteBuffer.allocate(
            1 + Long.BYTES + Long.BYTES + PAGE_SIZE
        );
        payload.put(PAGE_IMAGE);
        payload.putLong(transactionID);
        payload.putLong(pageID);
        payload.put(image);
        byte[] payloadBytes = payload.array();
        // Calculate CRC32 over the payload.
        CRC32 crc = new CRC32();
        crc.update(payloadBytes);
        // Length includes payload and checksum, but not the prefix.
        ByteBuffer record = ByteBuffer.allocate(
            Integer.BYTES + payloadBytes.length + Integer.BYTES
        );
        record.putInt(payloadBytes.length + Integer.BYTES);
        record.put(payloadBytes);
        record.putInt((int) crc.getValue());
        record.flip();
        appendRecord(record);
    }

    public void appendCommit(long transactionID) throws IOException {
        if(transactionID <= 0){
            throw new IllegalArgumentException("Invalid transaction ID");
        }
        ByteBuffer payload = ByteBuffer.allocate(
            1 + Long.BYTES
        );
        payload.put(COMMIT);
        payload.putLong(transactionID);
        byte[] payloadBytes = payload.array();
        CRC32 crc = new CRC32();
        crc.update(payloadBytes);
        ByteBuffer record = ByteBuffer.allocate(
            Integer.BYTES + payloadBytes.length + Integer.BYTES
        );
        record.putInt(payloadBytes.length + Integer.BYTES);
        record.put(payloadBytes);
        record.putInt((int) crc.getValue());
        record.flip();
        appendRecord(record);
    }

    public void flush() throws IOException{
        channel.force(true);
    }

    public long nextTransactionID(){
        return nextTransactionID++;
    }

    @Override 
    public void close() throws IOException{
        channel.close();
    }

    private ByteBuffer readRecord(long position, int size) throws IOException{
        ByteBuffer buffer = ByteBuffer.allocate(size);
        long filePosition = position;
        while(buffer.hasRemaining()){
            int bytesRead = channel.read(buffer, filePosition);
            if(bytesRead == -1){
                return null;
            }
            if(bytesRead == 0){
                continue;
            }
            filePosition += bytesRead;
        }
        buffer.flip();
        return buffer;
    }

    
    public LogRecord readNextRecord(long position) throws IOException {
        long fileSize = channel.size();
        if (position < 0 || position > fileSize) {
            throw new IOException("Invalid WAL position");
        }
        if (fileSize - position < Integer.BYTES) {
            return null;
        }
        ByteBuffer lengthBuffer = readRecord(position, Integer.BYTES);
        if (lengthBuffer == null) {
            return null;
        }
        int recordLength = lengthBuffer.getInt();
        if (recordLength != 13 && recordLength != 4117) {
            throw new IOException(
                "Invalid WAL record length: " + recordLength
            );
        }
        if (fileSize - position - Integer.BYTES < recordLength) {
            return null;
        }
        ByteBuffer body = readRecord(
            position + Integer.BYTES, recordLength
        );
        if (body == null) {
            return null;
        }
        body.order(ByteOrder.BIG_ENDIAN);
        int payloadLength = recordLength - Integer.BYTES;
        byte[] payloadBytes = new byte[payloadLength];
        body.get(payloadBytes);
        int storedChecksum = body.getInt();
        CRC32 crc = new CRC32();
        crc.update(payloadBytes);
        if ((int) crc.getValue() != storedChecksum) {
            throw new IOException(
                "WAL checksum mismatch at offset " + position
            );
        }
        // Parse only after validating the checksum.
        ByteBuffer payload = ByteBuffer.wrap(payloadBytes);
        payload.order(ByteOrder.BIG_ENDIAN);
        byte type = payload.get();
        long transactionID = payload.getLong();
        if (transactionID <= 0) {
            throw new IOException("Invalid transaction ID");
        }
        if (type == LogRecord.COMMIT) {
            if (recordLength != 13) {
                throw new IOException("Invalid commit record length");
            }
            return new LogRecord(type, transactionID, -1, null);
        }
        if (type == LogRecord.PAGE_IMAGE) {
            if (recordLength != 4117) {
                throw new IOException(
                    "Invalid page-image record length"
                );
            }
            long pageID = payload.getLong();
            if (pageID < 0) {
                throw new IOException("Invalid page ID");
            }
            byte[] pageImage = new byte[PAGE_SIZE];
            payload.get(pageImage);
            return new LogRecord(
                type, transactionID, pageID, pageImage
            );
        }
        throw new IOException("Unknown WAL record type: " + type);
    }


    public List<LogRecord> scanRecords() throws IOException{
        List<LogRecord> records = new ArrayList<>();
        long position = 0;
        long fileSize = channel.size();

        while(position < fileSize){
            LogRecord record = readNextRecord(position);
            if(record == null){
                channel.truncate(position);
                channel.force(true);
                break;
            }
            records.add(record);

            ByteBuffer lengthBuffer = readRecord(position, Integer.BYTES);
            if(lengthBuffer == null){
                throw new IOException("Unable to read WAL record length");
            }
            int recordLength = lengthBuffer.getInt();
            position += Integer.BYTES + recordLength;
        }
        channel.position(channel.size());
        return records;
    }

    
    private void appendRecord(ByteBuffer record) throws IOException {
        channel.position(channel.size());
        while (record.hasRemaining()) {
            int written = channel.write(record);
            if (written == 0) {
                Thread.yield();
            }
        }
    }

}
