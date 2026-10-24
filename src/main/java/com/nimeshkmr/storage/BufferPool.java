package com.nimeshkmr.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;

public class BufferPool {
    private Frame[] frames;
    private HashMap<Long, Integer> pageToFrame;
    private TreeMap<Long, Integer> evictionMap;
    private ArrayList<Integer> freeFrames;
    private DiskManager diskManager; 
    private long timestamp;

    public BufferPool(int n, DiskManager dm){
        if(n <= 0){
            throw new IllegalArgumentException();
        }
        this.frames = new Frame[n];
        this.diskManager = dm;
        this.pageToFrame = new HashMap<>();
        this.evictionMap = new TreeMap<>();
        this.freeFrames = new ArrayList<>();
        this.timestamp = 0;
        for(int i = 0; i < n; i++){
            this.freeFrames.add(i);
        }
    }

    public Page fetchPage(long pageID) throws IOException{
        Frame frame;
        if(pageToFrame.containsKey(pageID)){
            int frameID = pageToFrame.get(pageID);
            frame = frames[frameID];
            evictionMap.remove(frame.getTime());
        }
        else{
            int frameID;
            if(!freeFrames.isEmpty()){
                frameID = freeFrames.get(freeFrames.size()-1);
                freeFrames.remove(freeFrames.size()-1);
            }
            else{
                if(evictionMap.isEmpty()){
                    throw new IllegalStateException();
                }
                frameID = evictionMap.get(evictionMap.firstKey());
                evictionMap.remove(evictionMap.firstKey());
                Frame oldFrame = frames[frameID];
                if(oldFrame.isDirty()){
                    diskManager.writePage(oldFrame.getPageID(), oldFrame.getPage());
                }
                pageToFrame.remove(oldFrame.getPageID());
            }
            Page page = diskManager.readPage(pageID);
            frame = new Frame();
            frames[frameID] = frame;
            frame.setPageID(pageID);
            frame.setPage(page);
            pageToFrame.put(pageID, frameID);
        }
        frame.setTime(timestamp);
        timestamp++;
        frame.pin();
        return frame.getPage();
    }

    public void unpinPage(long pageID, boolean dirty){
        if(!pageToFrame.containsKey(pageID)){
            throw new IllegalStateException();
        }
        int frameID = pageToFrame.get(pageID);
        Frame frame = frames[frameID];
        if(dirty){
            frame.setDirty();
        }
        frame.unpin();
        if(frame.getPincount() == 0){
            evictionMap.put(frame.getTime(), frameID);
        }
    }

    public void flushPage(long pageID) throws IOException{
        if(!pageToFrame.containsKey(pageID)){
            throw new IllegalStateException();
        }
        int frameID = pageToFrame.get(pageID);
        Frame frame = frames[frameID];
        if(frame.isDirty()){
            diskManager.writePage(pageID, frame.getPage());
            frame.clearDirty();
        }
    }

    public void flushAllPages() throws IOException{
        for(int i = 0; i < frames.length; i++){
            Frame frame = frames[i];
            if(frame.isDirty()){
                diskManager.writePage(frame.getPageID(), frame.getPage());
                frame.clearDirty();
            }
        }
    }

}
