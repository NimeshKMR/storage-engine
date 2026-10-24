package com.nimeshkmr.storage;

public class LogRecord {
    public static final byte PAGE_IMAGE = 1;
    public static final byte COMMIT = 2;

    private final byte type;
    private final long transactionID;
    private final long pageID;
    private final byte[] pageImage;

    public LogRecord(byte type, long transactionID, long pageID,
                    byte[] pageImage){
        this.type = type;
        this.transactionID = transactionID;
        this.pageID = pageID;
        this.pageImage = pageImage;
    }

    public byte getType(){
        return type;
    }

    public long getTransactionID(){
        return transactionID;
    }

    public long getPageID(){
        return pageID;
    }

    public byte[] getPageImage(){
        return pageImage;
    }
    
    
}
