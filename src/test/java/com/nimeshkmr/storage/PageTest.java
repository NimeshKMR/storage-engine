package com.nimeshkmr.storage;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;


public class PageTest {


    @Test 
    void pageCapacityTest(){
        Page page = new Page();
        assertEquals(4096, page.getByteBuffer().capacity());
    }

    @Test
    void pageWriteReadTest(){
        Page page = new Page();
        ByteBuffer buffer = page.getByteBuffer();
        buffer.put((byte) 10);
        buffer.putInt(30);
        buffer.put((byte) 50);
        buffer.flip();
        byte firstValue = buffer.get();
        int secondValue = buffer.getInt();
        byte thirdValue = buffer.get();  
        assertEquals(10, firstValue);
        assertEquals(30, secondValue);
        assertEquals(50, thirdValue);
    }
}
