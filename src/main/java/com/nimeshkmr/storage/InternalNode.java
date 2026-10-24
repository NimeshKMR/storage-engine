package com.nimeshkmr.storage;

import java.nio.ByteBuffer;

public class InternalNode {

    private Page nodePage;
    private static final byte LEAF_NODE_TYPE = 0;
    private static final int NODE_TYPE_OFFSET = 0;
    private static final int NUM_KEYS_OFFSET = 1;
    private static final int FIRST_CHILD_OFFSET = 5;

    /** Physical capacity of a 4096-byte internal page: (4096 - 13) / 12 = 340 keys. */
    public static final int DEFAULT_MAX_KEYS = 340;
    /** Smallest capacity that keeps splits well-formed (used only for demo trees). */
    public static final int MIN_CAPACITY = 3;

    private final int maxKeys;

    public InternalNode(Page page){
        this(page, DEFAULT_MAX_KEYS);
    }

    /** @param maxKeys logical capacity, between MIN_CAPACITY and DEFAULT_MAX_KEYS */
    public InternalNode(Page page, int maxKeys){
        if (maxKeys < MIN_CAPACITY || maxKeys > DEFAULT_MAX_KEYS) {
            throw new IllegalArgumentException(
                "Internal capacity must be in [" + MIN_CAPACITY + ", " + DEFAULT_MAX_KEYS + "]: " + maxKeys);
        }
        this.maxKeys = maxKeys;
        this.nodePage = page;
    }

    public void initialize(long firstChildPageID){
        ByteBuffer bb = nodePage.getByteBuffer();
        bb.put(NODE_TYPE_OFFSET, LEAF_NODE_TYPE);
        bb.putInt(NUM_KEYS_OFFSET, 0);
        bb.putLong(FIRST_CHILD_OFFSET, firstChildPageID);
    }

    public int getNumKeys(){
        ByteBuffer bb = nodePage.getByteBuffer();
        return bb.getInt(NUM_KEYS_OFFSET);
    }

    public long getChildPageID(int index){
        if(index < 0 || index > this.getNumKeys()){
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer bb = nodePage.getByteBuffer();
        return bb.getLong(5 + 12*index);
    }

    public int getKey(int index){
        if(index < 0 || index >= this.getNumKeys()){
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer bb = nodePage.getByteBuffer();
        return bb.getInt(13 + 12*index);
    }

    public void insertKeyAndChild(int index, int key, long rightChildPageID){
        int numKeys = getNumKeys();
        if(index < 0 || index > numKeys){
            throw new IndexOutOfBoundsException();
        }
        if(numKeys >= maxKeys){
            throw new IllegalStateException();
        }
        ByteBuffer bb = nodePage.getByteBuffer();
        for(int i = numKeys-1; i >= index; i--){
            int oldKey = bb.getInt(13+12*i);
            bb.putInt(13+12*(i+1), oldKey);
        }
        for(int i = numKeys; i > index; i--){
            long oldChildPageID = bb.getLong(5+12*i);
            bb.putLong(5+12*(i+1), oldChildPageID);
        }
        bb.putInt(13+12*index, key);
        bb.putLong(5+12*(index+1), rightChildPageID);
        bb.putInt(NUM_KEYS_OFFSET, numKeys+1);
    }

    public int findChildIndex(int key){
        int left = 0;
        int right = this.getNumKeys();
        while(left < right){
            int mid = left + (right-left)/2;
            if(this.getKey(mid) <= key){
                left = mid + 1;
            }
            else{
                right = mid;
            }
        }
        return left;
    }

    public int split(Page newRightPage){
        int numKeys = this.getNumKeys();
        if(numKeys != maxKeys){
            throw new IllegalStateException("Internal Node must be full before splitting!");
        }
        int middleIndex = numKeys/2;
        int promotedKey = this.getKey(middleIndex);
        InternalNode rightNode = new InternalNode(newRightPage, maxKeys);
        rightNode.initialize(this.getChildPageID(middleIndex+1));
        for(int i = middleIndex + 1; i < numKeys; i++){
            rightNode.insertKeyAndChild(rightNode.getNumKeys(), this.getKey(i), 
                                            this.getChildPageID(i+1));
        }
        nodePage.getByteBuffer().putInt(NUM_KEYS_OFFSET, middleIndex);
        return promotedKey;
    }

    public int insertAndSplit(int index, int key, long rightChildPageID, Page newRightPage){
        int numKeys = this.getNumKeys();
        if(numKeys != maxKeys){
            throw new IllegalStateException();
        }
        if(index < 0 || index > numKeys){
            throw new IndexOutOfBoundsException();
        }
        int[] keys = new int[numKeys+1];
        long[] children = new long[numKeys+2];
        for(int i = 0; i < index; i++){
            keys[i] = this.getKey(i);
        }
        keys[index] = key;
        for(int i = index; i < numKeys; i++){
            keys[i+1] = this.getKey(i);
        }
        for(int i = 0; i <= index; i++){
            children[i] = this.getChildPageID(i);
        }
        children[index+1] = rightChildPageID;
        for(int i = index+1; i <= numKeys; i++){
            children[i+1] = this.getChildPageID(i);
        }

        int middleIndex = keys.length/2;
        int promotedKey = keys[middleIndex];

        initialize(children[0]);
        for(int i = 0; i < middleIndex; i++){
            insertKeyAndChild(i, keys[i], children[i+1]);
        }
        InternalNode rightNode = new InternalNode(newRightPage, maxKeys);
        rightNode.initialize(children[middleIndex+1]);
        for(int i = middleIndex + 1; i < keys.length; i++){
            rightNode.insertKeyAndChild(rightNode.getNumKeys(), keys[i], children[i+1]);
        }
        return promotedKey;
    }

    



}
