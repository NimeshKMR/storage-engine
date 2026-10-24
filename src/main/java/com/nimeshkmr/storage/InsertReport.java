package com.nimeshkmr.storage;

import java.util.List;

/**
 * Structured description of what one successful {@link BPlusTree#insertWithReport} did.
 *
 * <p>Every field is recorded by the engine while it performs the insert; nothing here is
 * inferred from the key afterwards. The report is only produced when the insert returns
 * normally. If the insert throws, no report exists.
 *
 * @param key               key that was inserted or updated
 * @param value             value now stored for the key
 * @param outcome           INSERTED for a new key, UPDATED if the key already existed
 * @param previousValue     old value when UPDATED, otherwise null
 * @param leafPageID        leaf page that held, or was chosen to receive, the key (before any split)
 * @param splits            node splits in the order they happened (empty if none)
 * @param previousRootPageID root page ID before the insert
 * @param rootPageID        root page ID after the insert
 * @param transactionID     WAL transaction ID, or -1 when the tree has no WAL
 * @param transactionState  state of the PageWriteTransaction after commit(), or null without WAL
 * @param pagesWritten      page IDs staged (and logged) by the transaction; empty without WAL
 * @param pagesAllocated    page IDs newly appended to the database file during this insert
 */
public record InsertReport(
        int key,
        int value,
        Outcome outcome,
        Integer previousValue,
        long leafPageID,
        List<Split> splits,
        long previousRootPageID,
        long rootPageID,
        long transactionID,
        PageWriteTransaction.State transactionState,
        List<Long> pagesWritten,
        List<Long> pagesAllocated) {

    public enum Outcome { INSERTED, UPDATED }

    public enum NodeKind { LEAF, INTERNAL }

    /**
     * One node split.
     *
     * @param kind            LEAF or INTERNAL
     * @param leftPageID      the page that was split (keeps the lower keys)
     * @param newRightPageID  the newly allocated page holding the upper keys
     * @param separatorKey    key passed up to the parent (first key of the new leaf, or the promoted key)
     * @param parentPageID    page that receives the separator; the new root's page when createdNewRoot
     * @param createdNewRoot  true if the split node was the root, so a new root was created
     */
    public record Split(
            NodeKind kind,
            long leftPageID,
            long newRightPageID,
            int separatorKey,
            long parentPageID,
            boolean createdNewRoot) {}

    public InsertReport {
        splits = List.copyOf(splits);
        pagesWritten = List.copyOf(pagesWritten);
        pagesAllocated = List.copyOf(pagesAllocated);
    }

    public boolean rootChanged() {
        return previousRootPageID != rootPageID;
    }
}
