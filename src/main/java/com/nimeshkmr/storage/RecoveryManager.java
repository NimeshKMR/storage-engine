package com.nimeshkmr.storage;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecoveryManager {
    
    public void recover(WriteAheadLog wal, DiskManager diskManager) throws IOException{
        List<LogRecord> records = wal.scanRecords();
        Set<Long> commitedTransactions = new HashSet<>();
        for(LogRecord record : records){
            if(record.getType() == LogRecord.COMMIT){
                commitedTransactions.add(record.getTransactionID());
            }
        }

        for(LogRecord record : records){
            if(record.getType() != LogRecord.PAGE_IMAGE){
                continue;
            }
            if(!commitedTransactions.contains(record.getTransactionID())){
                continue;
            }
            byte[] image = record.getPageImage();
            if(image == null || image.length != Page.PAGE_SIZE){
                throw new IOException("Invalid page image for page " + record.getPageID());
            }
            Page page = new Page();
            page.getByteBuffer().put(record.getPageImage());
            diskManager.restorePage(record.getPageID(), page);
        }
        diskManager.force();
    }
}
