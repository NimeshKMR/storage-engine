package com.nimeshkmr.storage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


public class Transaction {
    private final DiskManager diskManager;
    private final Map<Long, Page> modifiedPages = new HashMap<>();


    public Transaction(DiskManager diskManager){
        this.diskManager = diskManager;
    }

    public void stagePage(long pageID, Page page){
        modifiedPages.put(pageID, page);
    }

    public Map<Long, Page> getModifiedPages(){
        return Map.copyOf(modifiedPages);
    }

    public void commit() throws IOException{
        for(Map.Entry<Long, Page> entry : modifiedPages.entrySet()){
            diskManager.writePage(entry.getKey(), entry.getValue());
        }
        modifiedPages.clear();
    }

    

}
