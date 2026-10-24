package com.nimeshkmr.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BufferPoolTest {

    @Test
    void rejectsInvalidCapacity() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();

        assertThrows(IllegalArgumentException.class, () -> new BufferPool(0, dm));
        assertThrows(IllegalArgumentException.class, () -> new BufferPool(-1, dm));

        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void fetchPageLoadsPageIntoBufferPool() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        long pageID = dm.allocatePage();

        Page page = new Page();
        ByteBuffer buffer = page.getByteBuffer();
        buffer.put(new byte[]{1, 2, 3, 4, 5});
        dm.writePage(pageID, page);

        BufferPool pool = new BufferPool(2, dm);

        Page fetched = pool.fetchPage(pageID);
        ByteBuffer fetchedBuffer = fetched.getByteBuffer();

        byte[] result = new byte[5];
        fetchedBuffer.get(result);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);

        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void fetchingSamePageUsesSameFrame() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        long pageID = dm.allocatePage();

        BufferPool pool = new BufferPool(2, dm);

        Page first = pool.fetchPage(pageID);
        Page second = pool.fetchPage(pageID);

        assertSame(first, second);

        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void pageIsNotEvictableWhilePinned() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        dm.allocatePage();
        dm.allocatePage();
        dm.allocatePage();

        BufferPool pool = new BufferPool(2, dm);

        pool.fetchPage(0);
        pool.fetchPage(1);

        // Both frames are pinned, so page 2 cannot be loaded.
        assertThrows(IllegalStateException.class, () -> pool.fetchPage(2));

        dm.close();
        Files.deleteIfExists(path);
    }

    // @Test
    // void unpinnedPageCanBeEvicted() throws IOException {
    //     Path path = Path.of("database.db");
    //     Files.deleteIfExists(path);

    //     DiskManager dm = new DiskManager();
    //     dm.allocatePage();
    //     dm.allocatePage();
    //     dm.allocatePage();

    //     BufferPool pool = new BufferPool(2, dm);

    //     pool.fetchPage(0);
    //     pool.fetchPage(1);

    //     pool.unpinPage(0, false);
    //     pool.unpinPage(1, false);

    //     // Page 0 is the least recently used page.
    //     pool.fetchPage(2);

    //     // Page 0 should no longer be resident.
    //     assertThrows(IllegalStateException.class, () -> pool.unpinPage(0, false));

    //     dm.close();
    //     Files.deleteIfExists(path);
    // }

    @Test
    void pageRemainsPinnedUntilAllUsersUnpin() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        dm.allocatePage();
        dm.allocatePage();
        dm.allocatePage();

        BufferPool pool = new BufferPool(2, dm);

        pool.fetchPage(0);
        pool.fetchPage(0);

        pool.unpinPage(0, false);

        // Page 0 still has one active user.
        pool.fetchPage(1);

        // Page 0 is still pinned, so page 1 should be the evictable one.
        pool.unpinPage(1, false);

        pool.fetchPage(2);

        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void dirtyPageSurvivesEviction() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        dm.allocatePage();
        dm.allocatePage();

        BufferPool pool = new BufferPool(1, dm);

        Page page = pool.fetchPage(0);
        ByteBuffer buffer = page.getByteBuffer();
        buffer.put((byte) 42);

        pool.unpinPage(0, true);

        // This forces page 0 out of the only frame.
        pool.fetchPage(1);

        pool.unpinPage(1, false);

        // Fetch page 0 again. It must contain the modified value.
        Page recovered = pool.fetchPage(0);
        ByteBuffer recoveredBuffer = recovered.getByteBuffer();

        assertEquals(42, recoveredBuffer.get());

        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void cannotUnpinPageThatIsNotResident() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        dm.allocatePage();

        BufferPool pool = new BufferPool(1, dm);

        assertThrows(
                IllegalStateException.class,
                () -> pool.unpinPage(0, false)
        );

        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void cannotUnpinPageMoreTimesThanPinned() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        dm.allocatePage();

        BufferPool pool = new BufferPool(1, dm);

        pool.fetchPage(0);
        pool.unpinPage(0, false);

        assertThrows(
                IllegalStateException.class,
                () -> pool.unpinPage(0, false)
        );

        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void flushPagePersistsDirtyPage() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        dm.allocatePage();

        BufferPool pool = new BufferPool(1, dm);

        Page page = pool.fetchPage(0);
        page.getByteBuffer().put((byte) 42);

        pool.unpinPage(0, true);
        pool.flushPage(0);

        Page read = dm.readPage(0);
        assertEquals(42, read.getByteBuffer().get());

        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void flushAllPagesPersistsDirtyPages() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        dm.allocatePage();
        dm.allocatePage();

        BufferPool pool = new BufferPool(2, dm);

        Page page0 = pool.fetchPage(0);
        page0.getByteBuffer().put((byte) 10);
        pool.unpinPage(0, true);

        Page page1 = pool.fetchPage(1);
        page1.getByteBuffer().put((byte) 20);
        pool.unpinPage(1, true);

        pool.flushAllPages();

        Page read0 = dm.readPage(0);
        Page read1 = dm.readPage(1);

        assertEquals(10, read0.getByteBuffer().get());
        assertEquals(20, read1.getByteBuffer().get());

        dm.close();
        Files.deleteIfExists(path);
    }

    @Test
    void flushingCleanPageDoesNotFail() throws IOException {
        Path path = Path.of("database.db");
        Files.deleteIfExists(path);

        DiskManager dm = new DiskManager();
        dm.allocatePage();

        BufferPool pool = new BufferPool(1, dm);

        pool.fetchPage(0);
        pool.unpinPage(0, false);

        assertDoesNotThrow(() -> pool.flushPage(0));

        dm.close();
        Files.deleteIfExists(path);
    }
}