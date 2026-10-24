package com.nimeshkmr.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class LeafNode {
    
    private static final byte LEAF_NODE_TYPE = 1;
    private static final int NODE_TYPE_OFFSET = 0;
    private static final int NUM_KEYS_OFFSET = 1;
    private static final int NEXT_LEAF_OFFSET = 5;
    /** Physical capacity of a 4096-byte leaf page: (4096 - 13) / 8 = 510 entries. */
    public static final int DEFAULT_MAX_ENTRIES = 510;
    /** Smallest capacity that keeps splits well-formed (used only for demo trees). */
    public static final int MIN_CAPACITY = 3;

    private final int maxEntries;
    private final Page nodePage;

    public LeafNode(Page page){
        this(page, DEFAULT_MAX_ENTRIES);
    }

    /** @param maxEntries logical capacity, between MIN_CAPACITY and DEFAULT_MAX_ENTRIES */
    public LeafNode(Page page, int maxEntries){
        if (maxEntries < MIN_CAPACITY || maxEntries > DEFAULT_MAX_ENTRIES) {
            throw new IllegalArgumentException(
                "Leaf capacity must be in [" + MIN_CAPACITY + ", " + DEFAULT_MAX_ENTRIES + "]: " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.nodePage = page;
        this.nodePage.getByteBuffer().order(ByteOrder.BIG_ENDIAN);
    }

    public void initialize(){
        ByteBuffer bb = nodePage.getByteBuffer();
        bb.put(NODE_TYPE_OFFSET, LEAF_NODE_TYPE);
        bb.putInt(NUM_KEYS_OFFSET, 0);
        bb.putLong(NEXT_LEAF_OFFSET, -1L);
    }

    public int getNumKeys(){
        ByteBuffer bb = nodePage.getByteBuffer();
        return bb.getInt(NUM_KEYS_OFFSET);
    }

    public long getNextLeafPageID(){
        ByteBuffer bb = nodePage.getByteBuffer();
        return bb.getLong(NEXT_LEAF_OFFSET);
    }

    public int getKey(int index){
        if(index < 0 || index >= this.getNumKeys()){
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer bb = nodePage.getByteBuffer();
        return bb.getInt(13 + 8*index);
    }

    public int getValue(int index){
        if(index < 0 || index >= this.getNumKeys()){
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer bb = nodePage.getByteBuffer();
        return bb.getInt(17 + 8*index);
    }

    public void insertEntry(int index, int key, int value) {
        int numKeys = this.getNumKeys();
        if (index < 0 || index > numKeys) {
            throw new IndexOutOfBoundsException();
        }
        if (numKeys >= maxEntries) {
            throw new IllegalStateException("Leaf node is full");
        }
        ByteBuffer bb = nodePage.getByteBuffer();
        for (int i = numKeys - 1; i >= index; i--) {
            int oldKey = bb.getInt(13 + 8 * i);
            int oldValue = bb.getInt(17 + 8 * i);
            bb.putInt(13 + 8 * (i + 1), oldKey);
            bb.putInt(17 + 8 * (i + 1), oldValue);
        }
        bb.putInt(13 + 8 * index, key);
        bb.putInt(17 + 8 * index, value);
        bb.putInt(NUM_KEYS_OFFSET, numKeys + 1);
    }

    public void setNextLeafPageID(long nextLeafPageID){
        ByteBuffer bb = nodePage.getByteBuffer();
        bb.putLong(NEXT_LEAF_OFFSET, nextLeafPageID);
    }

    public int findKeyIndex(int key){
        int left = 0;
        int right = this.getNumKeys() - 1;

        while(left <= right){
            int mid = left + (right-left)/2;
            int midKey = this.getKey(mid);
            if(midKey == key){
                return mid;
            }
            else if(midKey < key){
                left = mid+1;
            }
            else{
                right = mid-1;
            }
        }
        return -1*(left+1);
    }

    public void setValue(int index, int value){
        if(index < 0 || index >= this.getNumKeys()){
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer bb = nodePage.getByteBuffer();
        bb.putInt(17 + 8*index, value);
        return;
    }

    public Page getPage() {
        return nodePage;
    }

    public void setEntry(int index, int key, int value){
        if(index < 0 || index >= this.getNumKeys()){
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer bb = nodePage.getByteBuffer();
        bb.putInt(13+8*index, key);
        bb.putInt(17+8*index, value);
        return;
    }

    public void copyEntryTo(int sourceIndex, LeafNode destination, int destinationIndex){
        int key = this.getKey(sourceIndex);
        int value = this.getValue(sourceIndex);
        destination.insertEntry(destinationIndex, key, value);
        return;
    }

    public void split(long newLeafPageID, Page newLeafPage){
        int originalNumKeys = this.getNumKeys();
        int splitIndex = originalNumKeys/2;
        LeafNode newLeaf = new LeafNode(newLeafPage);
        newLeaf.initialize();
        long oldNextLeafPageID = this.getNextLeafPageID();
        for(int i = splitIndex; i < originalNumKeys; i++){
            copyEntryTo(i, newLeaf, newLeaf.getNumKeys());
        }
        ByteBuffer bb = nodePage.getByteBuffer();
        bb.putInt(NUM_KEYS_OFFSET, splitIndex);
        newLeaf.setNextLeafPageID(oldNextLeafPageID);
        this.setNextLeafPageID(newLeafPageID);
        return;
    }

}
