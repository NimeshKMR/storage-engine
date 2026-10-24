
package com.nimeshkmr.storage;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class LeafNodeTest {

    private LeafNode createLeaf() {
        Page page = new Page();
        LeafNode leaf = new LeafNode(page);
        leaf.initialize();
        return leaf;
    }

    @Test
    void initializeEmptyLeaf() {
        LeafNode leaf = createLeaf();

        assertEquals(0, leaf.getNumKeys());
        assertEquals(-1L, leaf.getNextLeafPageID());
    }

    @Test
    void insertAndReadEntry() {
        LeafNode leaf = createLeaf();

        leaf.insertEntry(0, 10, 100);

        assertEquals(1, leaf.getNumKeys());
        assertEquals(10, leaf.getKey(0));
        assertEquals(100, leaf.getValue(0));
    }

    @Test
    void insertEntryInMiddleShiftsExistingEntries() {
        LeafNode leaf = createLeaf();

        leaf.insertEntry(0, 10, 100);
        leaf.insertEntry(1, 30, 300);
        leaf.insertEntry(2, 50, 500);

        leaf.insertEntry(1, 20, 200);

        assertEquals(4, leaf.getNumKeys());

        assertEquals(10, leaf.getKey(0));
        assertEquals(100, leaf.getValue(0));

        assertEquals(20, leaf.getKey(1));
        assertEquals(200, leaf.getValue(1));

        assertEquals(30, leaf.getKey(2));
        assertEquals(300, leaf.getValue(2));

        assertEquals(50, leaf.getKey(3));
        assertEquals(500, leaf.getValue(3));
    }

    @Test
    void insertEntryAtBeginning() {
        LeafNode leaf = createLeaf();

        leaf.insertEntry(0, 20, 200);
        leaf.insertEntry(0, 10, 100);

        assertEquals(2, leaf.getNumKeys());
        assertEquals(10, leaf.getKey(0));
        assertEquals(100, leaf.getValue(0));
        assertEquals(20, leaf.getKey(1));
        assertEquals(200, leaf.getValue(1));
    }

    @Test
    void insertEntryAtEnd() {
        LeafNode leaf = createLeaf();

        leaf.insertEntry(0, 10, 100);
        leaf.insertEntry(1, 20, 200);

        assertEquals(2, leaf.getNumKeys());
        assertEquals(20, leaf.getKey(1));
        assertEquals(200, leaf.getValue(1));
    }

    @Test
    void setAndGetNextLeafPageID() {
        LeafNode leaf = createLeaf();

        leaf.setNextLeafPageID(42L);

        assertEquals(42L, leaf.getNextLeafPageID());
    }

    @Test
    void rejectInvalidKeyIndex() {
        LeafNode leaf = createLeaf();
        leaf.insertEntry(0, 10, 100);

        assertThrows(IndexOutOfBoundsException.class,
                () -> leaf.getKey(-1));

        assertThrows(IndexOutOfBoundsException.class,
                () -> leaf.getKey(1));
    }

    @Test
    void rejectInvalidValueIndex() {
        LeafNode leaf = createLeaf();
        leaf.insertEntry(0, 10, 100);

        assertThrows(IndexOutOfBoundsException.class,
                () -> leaf.getValue(-1));

        assertThrows(IndexOutOfBoundsException.class,
                () -> leaf.getValue(1));
    }

    @Test
    void rejectInvalidInsertionIndex() {
        LeafNode leaf = createLeaf();

        assertThrows(IndexOutOfBoundsException.class,
                () -> leaf.insertEntry(-1, 10, 100));

        assertThrows(IndexOutOfBoundsException.class,
                () -> leaf.insertEntry(1, 10, 100));
    }

    @Test
    void rejectInsertionWhenLeafIsFull() {
        LeafNode leaf = createLeaf();

        for (int i = 0; i < 510; i++) {
            leaf.insertEntry(i, i, i * 10);
        }

        assertEquals(510, leaf.getNumKeys());

        assertThrows(IllegalStateException.class,
                () -> leaf.insertEntry(510, 510, 5100));

        assertEquals(510, leaf.getNumKeys());
    }

    @Test
    void entriesAreStoredInPageBuffer() {
        Page page = new Page();
        LeafNode leaf = new LeafNode(page);
        leaf.initialize();

        leaf.insertEntry(0, 123, 456);

        ByteBuffer bb = page.getByteBuffer();

        assertEquals(1, bb.getInt(1));
        assertEquals(123, bb.getInt(13));
        assertEquals(456, bb.getInt(17));
    }

    
    @Test
    void findExistingKeyInMiddle() {
        LeafNode leaf = createLeaf();

        leaf.insertEntry(0, 10, 100);
        leaf.insertEntry(1, 30, 300);
        leaf.insertEntry(2, 50, 500);
        leaf.insertEntry(3, 70, 700);

        assertEquals(2, leaf.findKeyIndex(50));
    }

    @Test
    void findMissingKeyBetweenEntries() {
        LeafNode leaf = createLeaf();

        leaf.insertEntry(0, 10, 100);
        leaf.insertEntry(1, 30, 300);
        leaf.insertEntry(2, 50, 500);
        leaf.insertEntry(3, 70, 700);

        assertEquals(-3, leaf.findKeyIndex(40));
    }

    @Test
    void findKeySmallerThanAllEntries() {
        LeafNode leaf = createLeaf();

        leaf.insertEntry(0, 10, 100);
        leaf.insertEntry(1, 30, 300);

        assertEquals(-1, leaf.findKeyIndex(5));
    }

    @Test
    void findKeyLargerThanAllEntries() {
        LeafNode leaf = createLeaf();

        leaf.insertEntry(0, 10, 100);
        leaf.insertEntry(1, 30, 300);

        assertEquals(-3, leaf.findKeyIndex(40));
    }

    @Test
    void findKeyInEmptyLeaf() {
        LeafNode leaf = createLeaf();

        assertEquals(-1, leaf.findKeyIndex(10));
    }

    @Test
    void findFirstAndLastKeys() {
        LeafNode leaf = createLeaf();

        leaf.insertEntry(0, 10, 100);
        leaf.insertEntry(1, 30, 300);
        leaf.insertEntry(2, 50, 500);

        assertEquals(0, leaf.findKeyIndex(10));
        assertEquals(2, leaf.findKeyIndex(50));
    }

    
    @Test
    void splitDividesEntriesEvenly() {
        Page leftPage = new Page();
        LeafNode left = new LeafNode(leftPage);
        left.initialize();

        for (int i = 0; i < 510; i++) {
            left.insertEntry(i, i, i * 100);
        }

        Page rightPage = new Page();
        left.split(2L, rightPage);

        LeafNode right = new LeafNode(rightPage);

        assertEquals(255, left.getNumKeys());
        assertEquals(255, right.getNumKeys());

        assertEquals(0, left.getKey(0));
        assertEquals(254, left.getKey(254));

        assertEquals(255, right.getKey(0));
        assertEquals(509, right.getKey(254));
    }

    @Test
    void splitMaintainsLeafLinks() {
        Page leftPage = new Page();
        LeafNode left = new LeafNode(leftPage);
        left.initialize();

        left.setNextLeafPageID(99L);

        for (int i = 0; i < 510; i++) {
            left.insertEntry(i, i, i * 10);
        }

        Page rightPage = new Page();
        left.split(2L, rightPage);

        LeafNode right = new LeafNode(rightPage);

        assertEquals(2L, left.getNextLeafPageID());
        assertEquals(99L, right.getNextLeafPageID());
    }

    @Test
    void splitPreservesKeyValuePairs() {
        Page leftPage = new Page();
        LeafNode left = new LeafNode(leftPage);
        left.initialize();

        for (int i = 0; i < 510; i++) {
            left.insertEntry(i, i, i + 1000);
        }

        Page rightPage = new Page();
        left.split(2L, rightPage);

        LeafNode right = new LeafNode(rightPage);

        assertEquals(254, left.getKey(254));
        assertEquals(1254, left.getValue(254));

        assertEquals(255, right.getKey(0));
        assertEquals(1255, right.getValue(0));

        assertEquals(509, right.getKey(254));
        assertEquals(1509, right.getValue(254));
    }


}
