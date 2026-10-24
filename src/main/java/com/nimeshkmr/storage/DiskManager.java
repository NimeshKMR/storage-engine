package com.nimeshkmr.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class DiskManager implements AutoCloseable {
    private FileChannel fileChannel;
    private static final int PAGE_SIZE = 4096;

    public DiskManager() throws IOException{
        Path path = Paths.get("database.db");
        this.fileChannel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE);
    }

    public DiskManager(Path path) throws IOException {
        this.fileChannel = FileChannel.open( path,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ,
            StandardOpenOption.CREATE
        );
    }
    public long getNumPages() throws IOException{
        long fileSize = fileChannel.size();
        if (fileSize % PAGE_SIZE != 0) {
        throw new IOException("Database file size is not page-aligned");
        }

        return fileSize / PAGE_SIZE;
    }

    public long allocatePage() throws IOException{
        Page newPage = new Page();
        long pageID = (long) (fileChannel.size()/PAGE_SIZE);
        fileChannel.position(fileChannel.size());
        while(newPage.getByteBuffer().hasRemaining()){
            this.fileChannel.write(newPage.getByteBuffer());
        }
        return pageID;
    }

    public Page readPage(long pageID) throws IOException{
        long offset = (long) pageID * PAGE_SIZE;
        if(offset < 0 || this.fileChannel.size() <= offset){
            throw new IllegalArgumentException("Page ID does not exist!");
        }
        Page read = new Page();
        ByteBuffer readBuffer = read.getByteBuffer();
        while(readBuffer.hasRemaining()){
            long bytesRead = this.fileChannel.read(readBuffer, offset + readBuffer.position());
            if(bytesRead == -1){
                throw new IOException();
            }
        }
        readBuffer.flip();
        return read;
    }

    
    public void writePage(long pageID, Page page) throws IOException {
        if (pageID < 0 || fileChannel.size() <= (long)pageID*PAGE_SIZE) {
            throw new IllegalArgumentException("Page ID does not exist!");
        }
        long offset;
        try {
            offset = Math.multiplyExact(pageID, (long) PAGE_SIZE);
        } catch (ArithmeticException e) {
            throw new IOException("Page ID is too large", e);
        }

        if (page.getByteBuffer().capacity() < PAGE_SIZE) {
            throw new IllegalArgumentException("Page buffer is too small");
        }
        ensurePageExists(pageID);
        ByteBuffer toWrite = page.getByteBuffer().duplicate();
        toWrite.position(0);
        toWrite.limit(PAGE_SIZE);
        writeFully(toWrite, offset);
    }

    /**
     * Extend the database file through pageID if necessary.
     * Newly created pages contain zeros.
     */
    private void ensurePageExists(long pageID) throws IOException {
        long fileSize = fileChannel.size();
        if (fileSize % PAGE_SIZE != 0) {
            throw new IOException("Database file size is not page-aligned");
        }
        long requiredSize;
        try {
            requiredSize = Math.multiplyExact(
                Math.addExact(pageID, 1L),
                (long) PAGE_SIZE
            );
        } catch (ArithmeticException e) {
            throw new IOException("Page ID is too large", e);
        }
        if (requiredSize <= fileSize) {
            return;
        }
        ByteBuffer zeros = ByteBuffer.allocate(PAGE_SIZE);
        while (fileSize < requiredSize) {
            zeros.clear();
            writeFully(zeros, fileSize);
            fileSize += PAGE_SIZE;
        }
    }

    private void writeFully(ByteBuffer buffer, long offset)
            throws IOException {
        while (buffer.hasRemaining()) {
            int written = fileChannel.write(
                buffer,
                offset + buffer.position()
            );
            if (written == 0) {
                Thread.yield();
            }
        }
    }

    public void restorePage(long pageID, Page page) throws IOException{
        if(pageID < 0){
            throw new IllegalArgumentException("Page ID cannot be negative");
        }
        ensurePageExists(pageID);
        writePage(pageID, page);
    }


    /**
     * Request that database changes be persisted to storage.
     */
    public void force() throws IOException {
        fileChannel.force(true);
    }


    public void close() throws IOException{
        this.fileChannel.close();
    }
}
