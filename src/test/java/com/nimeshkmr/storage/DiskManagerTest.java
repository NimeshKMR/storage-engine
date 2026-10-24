package com.nimeshkmr.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class DiskManagerTest {

    @Test
    void databaseFileIsCreated() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager diskManager = new DiskManager();

        assertTrue(Files.exists(path));
        assertEquals(0, Files.size(path));
        long id0 = diskManager.allocatePage();
        assertEquals(0, id0);
        assertEquals(4096, Files.size(path));
        long id1 = diskManager.allocatePage();
        assertEquals(8192, Files.size(path));
        assertEquals(1, id1);
        diskManager.close();
        Files.deleteIfExists(path);
    }

    @Test 
    void readWriteTest() throws IOException{
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);
        DiskManager dm = new DiskManager();
        dm.allocatePage();
        dm.allocatePage();
        Page page = new Page();
        ByteBuffer buffer = page.getByteBuffer();
        byte[] data = {1, 2, 3, 4, 5};
        buffer.put(data);
        dm.writePage(1, page);
        Page read = dm.readPage(1);
        ByteBuffer rb = read.getByteBuffer();
        byte[] result = new byte[data.length];
        rb.get(result);
        assertArrayEquals(data, result);
        dm.close();
    }

    @Test
    void readNegativePageIdFails() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);
        DiskManager dm = new DiskManager();
        dm.allocatePage();
        assertThrows(IllegalArgumentException.class, () -> dm.readPage(-1));
        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void readUnallocatedPageFails() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);
        DiskManager dm = new DiskManager();
        dm.allocatePage();
        assertThrows(IllegalArgumentException.class, () -> dm.readPage(1));
        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void writeNegativePageIdFails() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);
        DiskManager dm = new DiskManager();
        dm.allocatePage();
        Page page = new Page();
        assertThrows(IllegalArgumentException.class, () -> dm.writePage(-1, page));
        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void writeUnallocatedPageFails() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);
        DiskManager dm = new DiskManager();
        dm.allocatePage();
        Page page = new Page();
        assertThrows(IllegalArgumentException.class, () -> dm.writePage(1, page));
        dm.close();
        Files.deleteIfExists(path);
    }

    @Test 
    void processTimePersistence() throws IOException{
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);
        DiskManager dm1 = new DiskManager();
        dm1.allocatePage();
        Page page = new Page();
        ByteBuffer buffer = page.getByteBuffer();
        byte[] data = {1, 2, 3, 4, 5};
        buffer.put(data);
        dm1.writePage(0, page);
        dm1.close();
        DiskManager dm2 = new DiskManager();
        Page read = dm2.readPage(0);
        ByteBuffer rb = read.getByteBuffer();
        byte[] result = new byte[data.length];
        rb.get(result);
        assertArrayEquals(data, result);
        dm2.close();
        Files.deleteIfExists(path);
    }

    @Test 
    void numPages() throws IOException{
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);
        DiskManager dm1 = new DiskManager();
        assertEquals(0, dm1.getNumPages());
        dm1.allocatePage();
        assertEquals(1, dm1.getNumPages());
        dm1.close();
        Files.deleteIfExists(path);
    }
}