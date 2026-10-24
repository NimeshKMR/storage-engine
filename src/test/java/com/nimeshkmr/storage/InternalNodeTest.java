package com.nimeshkmr.storage;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InternalNodeTest {

    @Test
    void initializeInternalNode() {
        Page page = new Page();
        InternalNode node = new InternalNode(page);

        node.initialize(10L);

        assertEquals(0, node.getNumKeys());
        assertEquals(10L, node.getChildPageID(0));
    }

    @Test
    void insertKeyAndChild() {
        Page page = new Page();
        InternalNode node = new InternalNode(page);

        node.initialize(10L);
        node.insertKeyAndChild(0, 50, 20L);

        assertEquals(1, node.getNumKeys());
        assertEquals(50, node.getKey(0));
        assertEquals(10L, node.getChildPageID(0));
        assertEquals(20L, node.getChildPageID(1));
    }

    @Test
    void insertMultipleKeysAndChildren() {
        Page page = new Page();
        InternalNode node = new InternalNode(page);

        node.initialize(10L);
        node.insertKeyAndChild(0, 50, 20L);
        node.insertKeyAndChild(1, 80, 30L);

        assertEquals(2, node.getNumKeys());
        assertEquals(50, node.getKey(0));
        assertEquals(80, node.getKey(1));

        assertEquals(10L, node.getChildPageID(0));
        assertEquals(20L, node.getChildPageID(1));
        assertEquals(30L, node.getChildPageID(2));
    }

    @Test
    void rejectInvalidKeyIndex() {
        Page page = new Page();
        InternalNode node = new InternalNode(page);

        node.initialize(10L);

        assertThrows(IndexOutOfBoundsException.class,
                () -> node.getKey(0));

        node.insertKeyAndChild(0, 50, 20L);

        assertThrows(IndexOutOfBoundsException.class,
                () -> node.getKey(-1));

        assertThrows(IndexOutOfBoundsException.class,
                () -> node.getKey(1));
    }

    @Test
    void rejectInvalidChildIndex() {
        Page page = new Page();
        InternalNode node = new InternalNode(page);

        node.initialize(10L);

        assertThrows(IndexOutOfBoundsException.class,
                () -> node.getChildPageID(-1));

        assertThrows(IndexOutOfBoundsException.class,
                () -> node.getChildPageID(1));
    }

    @Test
    void shouldSplitFullInternalNode() {
        Page leftPage = new Page();
        InternalNode left = new InternalNode(leftPage);

        // Initialize with child 0.
        left.initialize(1000);

        // Fill the node with 340 keys and 341 child pointers.
        for (int i = 0; i < 340; i++) {
            left.insertKeyAndChild(i, i * 10, 1001L + i);
        }

        Page rightPage = new Page();

        // Split and receive the promoted key.
        int promotedKey = left.split(rightPage);

        InternalNode right = new InternalNode(rightPage);

        // The middle key should be promoted.
        assertEquals(1700, promotedKey);

        // The original node becomes the left node.
        assertEquals(170, left.getNumKeys());

        // The new node contains the right half.
        assertEquals(169, right.getNumKeys());

        // Check keys immediately around the split.
        assertEquals(1690, left.getKey(169));
        assertEquals(1710, right.getKey(0));

        // Check the child pointers around the split.
        assertEquals(1170L, left.getChildPageID(170));
        assertEquals(1171L, right.getChildPageID(0));

        // Check the first and last keys of the right node.
        assertEquals(1710, right.getKey(0));
        assertEquals(3390, right.getKey(168));
    }

    @Test
    void shouldRejectSplitWhenInternalNodeIsNotFull() {
        Page page = new Page();
        InternalNode node = new InternalNode(page);

        node.initialize(1000);
        node.insertKeyAndChild(0, 10, 1001);

        Page rightPage = new Page();

        assertThrows(
            IllegalStateException.class,
            () -> node.split(rightPage)
        );
    }

    @Test
    void shouldInsertIntoFullNodeAndSplit() {
        Page leftPage = new Page();
        InternalNode left = new InternalNode(leftPage);
        left.initialize(1000);

        // Fill the node with 340 keys.
        for (int i = 0; i < 340; i++) {
            left.insertKeyAndChild(i, i * 10, 1001L + i);
        }

        // Insert key 1505 between existing keys 1500 and 1510.
        // The new right child has page ID 9999.
        Page rightPage = new Page();

        int promotedKey = left.insertAndSplit(
            151,
            1505,
            9999L,
            rightPage
        );

        InternalNode right = new InternalNode(rightPage);

        // The combined sorted keys should contain 341 keys.
        // Middle index = 170, so the promoted key is 1700.
        assertEquals(1690, promotedKey);

        // Left node contains the 170 keys before the promoted key.
        assertEquals(170, left.getNumKeys());

        // Right node contains the 170 keys after the promoted key.
        assertEquals(170, right.getNumKeys());

        // Check keys around the split boundary.
        assertEquals(1680, left.getKey(169));
        assertEquals(1700, right.getKey(0));

        // The new child pointer must remain attached to key 1505.
        assertEquals(9999L, left.getChildPageID(152));

        // Verify the right node's first child pointer.
        assertEquals(1170L, right.getChildPageID(0));
    }
}

