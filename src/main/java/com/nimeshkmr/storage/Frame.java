package com.nimeshkmr.storage;

public class Frame {

    private long pageID;
    private Page page;
    private int pinCount;
    private boolean dirty;
    private long timestamp;

    public Frame(){
        this.dirty = false;
        this.pinCount = 0;
        this.page = null;
        this.pageID = -1;
        this.timestamp = -1;
    }

    public boolean isDirty(){
        return this.dirty;
    }

    public void setDirty(){
        this.dirty = true;
    }

    public void clearDirty(){
        this.dirty = false;
    }

    public void pin(){
        this.pinCount++;
    }

    public void unpin(){
        if (pinCount == 0) {
            throw new IllegalStateException();
        }
        this.pinCount--;
    }

    public void setPageID(long pageID){
        this.pageID = pageID;
    }

    public void setPage(Page page){
        this.page = page;
    }

    public Page getPage(){
        return this.page;
    }

    public long getPageID(){
        return this.pageID;
    }

    public void setTime(long time){
        this.timestamp = time;
    }

    public int getPincount(){
        return this.pinCount;
    }

    public long getTime(){
        return this.timestamp;
    }
    
    public void reset() {
        pageID = -1;
        page = null;
        pinCount = 0;
        dirty = false;
        timestamp = -1;
    }
}
