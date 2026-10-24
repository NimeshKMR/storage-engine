package com.nimeshkmr.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BPlusTree {

    
    private final DiskManager diskManager;
    private final WriteAheadLog wal;
    private PageWriteTransaction activeTransaction;
    private long rootPageID;
    private static final int MAGIC_NUMBER = 0x42505431;
    private static final int NODE_TYPE_OFFSET = 0;
    private boolean recoveryRequired = false;
    private final ReentrantReadWriteLock treeLock =
        new ReentrantReadWriteLock();
    private final int leafCapacity;
    private final int internalCapacity;

    public BPlusTree(DiskManager dm) {
        this(dm, null);
    }

    public BPlusTree(DiskManager dm, WriteAheadLog wal) {
        this(dm, wal, LeafNode.DEFAULT_MAX_ENTRIES, InternalNode.DEFAULT_MAX_KEYS);
    }

    /**
     * Creates a tree whose nodes split at a smaller logical capacity than the physical page
     * allows. Intended for demos, where a 3-level tree is needed without ~90,000 inserts.
     *
     * <p>The capacities are NOT stored in the database file. A database must always be reopened
     * with the capacities it was built with (or larger ones); reopening a tree whose nodes hold
     * more entries than the given capacity makes later inserts into those nodes fail.
     *
     * @param leafCapacity     max entries per leaf, in [LeafNode.MIN_CAPACITY, 510]
     * @param internalCapacity max keys per internal node, in [InternalNode.MIN_CAPACITY, 340]
     */
    public BPlusTree(DiskManager dm, WriteAheadLog wal, int leafCapacity, int internalCapacity) {
        if (leafCapacity < LeafNode.MIN_CAPACITY || leafCapacity > LeafNode.DEFAULT_MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid leaf capacity: " + leafCapacity);
        }
        if (internalCapacity < InternalNode.MIN_CAPACITY
                || internalCapacity > InternalNode.DEFAULT_MAX_KEYS) {
            throw new IllegalArgumentException("Invalid internal capacity: " + internalCapacity);
        }
        this.diskManager = dm;
        this.wal = wal;
        this.leafCapacity = leafCapacity;
        this.internalCapacity = internalCapacity;
    }

    public int getLeafCapacity() {
        return leafCapacity;
    }

    public int getInternalCapacity() {
        return internalCapacity;
    }

    /** True after a failed commit left the tree unusable until it is reopened and recovered. */
    public boolean isRecoveryRequired() {
        treeLock.readLock().lock();
        try {
            return recoveryRequired;
        } finally {
            treeLock.readLock().unlock();
        }
    }

    /** Callback run by {@link #readConsistently} while the tree's read lock is held. */
    @FunctionalInterface
    public interface TreeReader<T> {
        T read(DiskManager diskManager, long rootPageID) throws IOException;
    }

    /**
     * Runs {@code reader} under the tree's read lock. Writers (which hold the write lock for a
     * whole insert, including the WAL commit and page apply) cannot run concurrently, so the
     * reader sees only fully applied states and no active transaction.
     *
     * <p>The reader must not modify the database file and must not call back into this tree's
     * write operations.
     */
    public <T> T readConsistently(TreeReader<T> reader) throws IOException {
        treeLock.readLock().lock();
        try {
            return reader.read(diskManager, rootPageID);
        } finally {
            treeLock.readLock().unlock();
        }
    }


    private void initializeNewTree() throws IOException{
        long metadataPageID = this.allocatePage();
        if(metadataPageID != 0){
            throw new IOException();
        }
        this.rootPageID = this.allocatePage();
        Page rootLeafPage = new Page();
        ByteBuffer bb = rootLeafPage.getByteBuffer();
        bb.put((byte) 1);
        bb.putInt(0);
        bb.putLong(-1);
        Page metaDataPage = new Page();
        ByteBuffer mdbb = metaDataPage.getByteBuffer();
        mdbb.putInt(MAGIC_NUMBER);
        mdbb.putLong(rootPageID);
        this.writePage(rootPageID, rootLeafPage);
        this.writePage(metadataPageID, metaDataPage); 
    }

    private void initializeExistingTree() throws IOException{
        Page metaDataPage = this.readPage(0);
        ByteBuffer mdbb = metaDataPage.getByteBuffer();
        int magic_num = mdbb.getInt(0);
        if(magic_num != MAGIC_NUMBER){
            throw new IOException();
        }
        this.rootPageID = mdbb.getLong(4);
        long numPages = diskManager.getNumPages();
        if(rootPageID < 0 || rootPageID >= numPages){
            throw new IOException();
        }
    }

    
    public void initialize() throws IOException {
        treeLock.writeLock().lock();
        try{
        // Recovery must happen before reading metadata or tree nodes.
        if (wal != null) {
            new RecoveryManager().recover(wal, diskManager);
        }

        long numPages = diskManager.getNumPages();

        if (numPages == 0) {
            if (wal == null) {
                initializeNewTree();
                return;
            }

            PageWriteTransaction transaction = new PageWriteTransaction(
                wal.nextTransactionID(),
                wal,
                diskManager
            );

            activeTransaction = transaction;

            try {
                initializeNewTree();
                transaction.commit();
            } catch (IOException | RuntimeException e) {
                try {
                    transaction.abort();
                } catch (IllegalStateException ignored) {
                    // The transaction may already have completed.
                }
                throw e;
            } finally {
                activeTransaction = null;
            }
        } else {
            initializeExistingTree();
        }
        recoveryRequired = false;
        } finally{
            treeLock.writeLock().unlock();
        }
    }

    public long getRootPageID(){
        treeLock.readLock().lock();
        try{
        return this.rootPageID;
        } finally {
            treeLock.readLock().unlock();
        }
    }


    /** Mutable scratch record filled in by one insert when a report was requested. */
    private static final class InsertTrace {
        long leafPageID = -1;
        InsertReport.Outcome outcome;
        Integer previousValue;
        final List<InsertReport.Split> splits = new ArrayList<>();
    }

    private void insertInternal(int key, int value, InsertTrace trace) throws IOException {
        List<Long> path = new ArrayList<>();
        long leafPageID = findLeafPageID(key, path);
        Page leafPage = this.readPage(leafPageID);
        LeafNode leaf = new LeafNode(leafPage, leafCapacity);
        int index = leaf.findKeyIndex(key);
        if (trace != null) {
            trace.leafPageID = leafPageID;
        }
        if (index >= 0) {
            if (trace != null) {
                trace.outcome = InsertReport.Outcome.UPDATED;
                trace.previousValue = leaf.getValue(index);
            }
            leaf.setValue(index, value);
            this.writePage(leafPageID, leafPage);
            return;
        }
        if (trace != null) {
            trace.outcome = InsertReport.Outcome.INSERTED;
        }
        int insertionIndex = -index - 1;

        if (leaf.getNumKeys() < leafCapacity) {
            leaf.insertEntry(insertionIndex, key, value);
            this.writePage(leafPageID, leafPage);
            return;
        }

        long newLeafPageID = this.allocatePage();
        Page newLeafPage = new Page();

        leaf.split(newLeafPageID, newLeafPage);

        LeafNode newLeaf = new LeafNode(newLeafPage, leafCapacity);

        // Insert the incoming key into the appropriate half.
        if (key < newLeaf.getKey(0)) {
            int newIndex = -leaf.findKeyIndex(key) - 1;
            leaf.insertEntry(newIndex, key, value);
        } else {
            int newIndex = -newLeaf.findKeyIndex(key) - 1;
            newLeaf.insertEntry(newIndex, key, value);
        }

        // The first key in the right leaf becomes the separator.
        int separatorKey = newLeaf.getKey(0);

        this.writePage(leafPageID, leafPage);
        this.writePage(newLeafPageID, newLeafPage);

        insertIntoParent(leafPageID, separatorKey, newLeafPageID, path,
                InsertReport.NodeKind.LEAF, trace);
    }

    public void insert(int key, int value) throws IOException {
        insertLocked(key, value, false);
    }

    /**
     * Same as {@link #insert}, but also returns what the engine actually did: update vs insert,
     * every node split with its separator key and new page, root changes, and (with a WAL) the
     * transaction ID, its final state and the pages it wrote. Nothing in the report is inferred.
     */
    public InsertReport insertWithReport(int key, int value) throws IOException {
        return insertLocked(key, value, true);
    }

    private InsertReport insertLocked(int key, int value, boolean wantReport) throws IOException {
        treeLock.writeLock().lock();
        try{
        ensureTreeUsable();

        InsertTrace trace = wantReport ? new InsertTrace() : null;
        long numPagesBefore = wantReport ? diskManager.getNumPages() : 0;
        long previousRootPageID = rootPageID;

        if (wal == null) {
            insertInternal(key, value, trace);
            return wantReport
                ? buildReport(key, value, trace, previousRootPageID, null, numPagesBefore)
                : null;
        }

        if (activeTransaction != null) {
            throw new IllegalStateException(
                "Nested B+ tree transactions are not supported"
            );
        }

        PageWriteTransaction transaction = new PageWriteTransaction(
            wal.nextTransactionID(),
            wal,
            diskManager
        );

        activeTransaction = transaction;

        try {
            insertInternal(key, value, trace);
            transaction.commit();

        } catch (IOException | RuntimeException e) {
            PageWriteTransaction.State state = transaction.getState();

            if (state == PageWriteTransaction.State.ACTIVE) {
                transaction.abort();
                rootPageID = previousRootPageID;
            } else {
                // Commit may be durable, or its durability may be uncertain.
                // Do not allow operations on potentially inconsistent state.
                recoveryRequired = true;
            }

            throw e;

        } finally {
            activeTransaction = null;
        }

        return wantReport
            ? buildReport(key, value, trace, previousRootPageID, transaction, numPagesBefore)
            : null;
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    private InsertReport buildReport(int key, int value, InsertTrace trace,
            long previousRootPageID, PageWriteTransaction transaction,
            long numPagesBefore) throws IOException {
        long numPagesAfter = diskManager.getNumPages();
        List<Long> allocated = new ArrayList<>();
        for (long pageID = numPagesBefore; pageID < numPagesAfter; pageID++) {
            allocated.add(pageID);
        }
        return new InsertReport(
            key,
            value,
            trace.outcome,
            trace.previousValue,
            trace.leafPageID,
            trace.splits,
            previousRootPageID,
            rootPageID,
            transaction == null ? -1 : transaction.getTransactionID(),
            transaction == null ? null : transaction.getState(),
            transaction == null ? List.of() : transaction.getStagedPageIDs(),
            allocated
        );
    }

    private boolean isLeaf(Page page){
        ByteBuffer bb = page.getByteBuffer();
        if(bb.get(NODE_TYPE_OFFSET) == 1){
            return true;
        }
        else{
            return false;
        }
    }

    public Integer search(int key) throws IOException{
        treeLock.readLock().lock();
        try{
        ensureTreeUsable();
        return searchFromPage(rootPageID, key);
        } finally {
            treeLock.readLock().unlock();
        }
    }

    private Integer searchFromPage(long pageID, int key) throws IOException{
        Page page = this.readPage(pageID);
        if(isLeaf(page)){
            LeafNode leaf = new LeafNode(page);
            int keyIndex = leaf.findKeyIndex(key);
            if(keyIndex < 0){
                return null;
            }
            return leaf.getValue(keyIndex);
        }
        InternalNode internal = new InternalNode(page);
        int childIndex = internal.findChildIndex(key);
        long childPageID = internal.getChildPageID(childIndex);
        return searchFromPage(childPageID, key);
    }

    private long findLeafPageID(int key) throws IOException{
        long currentPageID = rootPageID;
        while(true){
            Page page = this.readPage(currentPageID);
            if(isLeaf(page)){
                return currentPageID;
            }
            InternalNode internal = new InternalNode(page);
            int childIndex = internal.findChildIndex(key);
            currentPageID = internal.getChildPageID(childIndex);
        }
    }

    private long findParentPageID(int key) throws IOException{
        long currentPageID = rootPageID;
        long parentPageID = -1;

        while(true){
            Page page = this.readPage(currentPageID);
            if(isLeaf(page)){
                return parentPageID;
            }
            parentPageID = currentPageID;

            InternalNode internal = new InternalNode(page);
            int childIndex = internal.findChildIndex(key);
            currentPageID = internal.getChildPageID(childIndex);
        }
    }

    private long findLeafPageID(int key, List<Long> path) throws IOException {
        long currentPageID = rootPageID;
        while (true) {
            Page page = this.readPage(currentPageID);
            if (isLeaf(page)) {
                return currentPageID;
            }
            path.add(currentPageID);

            InternalNode internal = new InternalNode(page);
            int childIndex = internal.findChildIndex(key);

            currentPageID = internal.getChildPageID(childIndex);
        }
    }

    private void insertIntoParent(long leftPageID,int separatorKey, long rightPageID,
            List<Long> path, InsertReport.NodeKind splitKind, InsertTrace trace)
            throws IOException {
        // No parent means the split happened at the root.
        if (path.isEmpty()) {
            long newRootPageID = this.allocatePage();

            if (trace != null) {
                trace.splits.add(new InsertReport.Split(
                    splitKind, leftPageID, rightPageID, separatorKey, newRootPageID, true));
            }

            Page newRootPage = new Page();
            InternalNode newRoot = new InternalNode(newRootPage, internalCapacity);

            newRoot.initialize(leftPageID);
            newRoot.insertKeyAndChild(
                0, separatorKey, rightPageID
            );

            this.writePage(newRootPageID, newRootPage);

            rootPageID = newRootPageID;

            Page metadataPage = this.readPage(0);
            metadataPage.getByteBuffer().putLong(4, rootPageID);
            this.writePage(0, metadataPage);

            return;
        }

        // Get the parent closest to the split.
        long parentPageID = path.remove(path.size() - 1);
        Page parentPage = this.readPage(parentPageID);
        InternalNode parent = new InternalNode(parentPage, internalCapacity);

        if (trace != null) {
            trace.splits.add(new InsertReport.Split(
                splitKind, leftPageID, rightPageID, separatorKey, parentPageID, false));
        }

        // Find the exact child pointer to the left node.
        int childIndex = -1;

        for (int i = 0; i <= parent.getNumKeys(); i++) {
            if (parent.getChildPageID(i) == leftPageID) {
                childIndex = i;
                break;
            }
        }

        if (childIndex == -1) {
            throw new IOException("Parent does not reference left child");
        }

        // If the parent has room, insert the separator normally.
        if (parent.getNumKeys() < internalCapacity) {
            parent.insertKeyAndChild(
                childIndex,
                separatorKey,
                rightPageID
            );

            this.writePage(parentPageID, parentPage);
            return;
        }

        // The parent is full: split it while inserting the separator.
        long newRightInternalPageID = this.allocatePage();
        Page newRightInternalPage = new Page();

        int promotedKey = parent.insertAndSplit(
            childIndex,
            separatorKey,
            rightPageID,
            newRightInternalPage
        );

        this.writePage(parentPageID, parentPage);
        this.writePage(
            newRightInternalPageID,
            newRightInternalPage
        );

        // Propagate the internal split to the next level.
        insertIntoParent(
            parentPageID,
            promotedKey,
            newRightInternalPageID,
            path,
            InsertReport.NodeKind.INTERNAL,
            trace
        );
    }
    private Page readPage(long pageID) throws IOException {
        if (activeTransaction != null) {
            return activeTransaction.readPage(pageID);
        }
        return diskManager.readPage(pageID);
    }

    private void writePage(long pageID, Page page) throws IOException {
        if (activeTransaction != null) {
            activeTransaction.writePage(pageID, page);
        } else {
            diskManager.writePage(pageID, page);
        }
    }

    private long allocatePage() throws IOException {
        if (activeTransaction != null) {
            return activeTransaction.allocatePage();
        }
        return diskManager.allocatePage();
    }

    private void ensureTreeUsable() {
    if (recoveryRequired) {
        throw new IllegalStateException(
            "Tree requires successful recovery before further operations"
        );
    }
}


}
