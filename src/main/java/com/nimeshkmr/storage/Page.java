package com.nimeshkmr.storage;

import java.nio.*;

public class Page {
    static final int PAGE_SIZE = 4096;
    private ByteBuffer buffer;

    public Page(){
        buffer = ByteBuffer.allocate(PAGE_SIZE);
    }

    public int getPageSize(){
        return PAGE_SIZE;
    }
    public ByteBuffer getByteBuffer(){
        return buffer;
    }
}