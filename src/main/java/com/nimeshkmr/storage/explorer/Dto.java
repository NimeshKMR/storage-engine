package com.nimeshkmr.storage.explorer;

import java.util.List;

/**
 * Immutable response objects. Records are serialized by {@link Json} with their component names
 * as camelCase field names, so renaming a component changes the HTTP API.
 */
public final class Dto {

    private Dto() {}

    /** One B+ tree node as stored in one page. Keys only; values are available per page. */
    public record NodeInfo(
            long pageId,
            String type,            // LEAF or INTERNAL
            int depth,              // root = 0
            int numKeys,
            List<Integer> keys,
            List<Long> childPageIds, // internal nodes: numKeys + 1 entries
            long nextLeafPageId,     // leaves: -1 when last; internal nodes: -1
            boolean root) {}

    /** Whole tree, read under the tree's read lock (see BPlusTree#readConsistently). */
    public record TreeSnapshot(
            long rootPageId,
            int height,
            long totalPages,        // pages in the file, including metadata page 0
            int nodeCount,          // pages reachable from the root
            int leafCount,
            int internalCount,
            long keyCount,
            int leafCapacity,
            int internalCapacity,
            List<NodeInfo> nodes,
            List<Long> leafOrder,   // leaves in left-to-right tree order
            List<Long> leafChain,   // leaves following the next-leaf pointers from the leftmost leaf
            boolean leafChainConsistent,
            List<String> invariantViolations) {}

    /** One internal node visited while descending to a leaf. */
    public record SearchStep(
            long pageId,
            int depth,
            int numKeys,
            int childIndex,
            long nextPageId,
            Integer lowerBound,     // keys[childIndex-1], inclusive; null at the left edge
            Integer upperBound) {}  // keys[childIndex], exclusive; null at the right edge

    public record SearchTrace(
            int key,
            boolean found,
            Integer value,
            boolean engineFound,    // result of BPlusTree#search for the same key, same lock hold
            Integer engineValue,
            boolean traceMatchesEngine,
            long rootPageId,
            List<SearchStep> path,
            long leafPageId,
            int leafDepth,
            int leafKeyCount,
            int foundIndex,         // slot of the key in the leaf, -1 if absent
            int insertionIndex,     // slot where the key is or would be placed
            Integer keyBefore,
            Integer keyAfter,
            boolean leafAtCapacity,
            int leafCapacity) {}

    public record PageInfo(
            long pageId,
            int pageSize,
            long byteOffset,
            String role,            // METADATA, LEAF, INTERNAL, UNUSED, INVALID
            boolean root,
            boolean reachable,      // reachable from the current root (always true for page 0)
            boolean allZero,
            int numKeys,
            List<Integer> keys,
            List<Integer> values,   // leaves only
            List<Long> childPageIds,
            long nextLeafPageId,
            Long metadataRootPageId,
            Boolean magicOk,
            String magicHex,
            String hexPreview,      // null unless requested
            String note) {}

    public record PageSummary(long pageId, String role, boolean root, boolean reachable, int numKeys) {}

    public record PageList(
            long totalPages, int returned, boolean truncated, long rootPageId, List<PageSummary> pages) {}

    public record WalRecordInfo(
            int index,
            long offset,
            int sizeBytes,
            String type,            // PAGE_IMAGE or COMMIT
            long transactionId,
            Long pageId,
            String imageNodeType,   // METADATA, LEAF, INTERNAL_OR_EMPTY, UNKNOWN (page images only)
            Integer imageNumKeys,
            Long imageRootPageId,   // metadata page images only
            boolean commitRecordPresent) {}

    public record WalSummary(
            int totalRecords,
            int pageImageRecords,
            int commitRecords,
            int transactions,
            int transactionsWithCommitRecord,
            List<Long> transactionsWithoutCommitRecord,
            long maxTransactionId,
            boolean scanTruncated) {}

    public record WalView(
            long walBytes,
            long offset,
            long nextOffset,        // -1 when there are no more records
            List<WalRecordInfo> records,
            WalSummary summary,
            long trailingBytes) {}  // bytes after the last complete record

    public record DatabaseStatus(
            String name,
            String state,           // OPEN or CLOSED (CLOSED = files exist, engine not opened by this server)
            long dbBytes,
            long walBytes,
            int leafCapacity,
            int internalCapacity,
            Long rootPageId,        // open databases only
            Long totalPages,
            Boolean recoveryRequired) {}

    public record BulkResult(
            int requested,
            int inserted,
            int updated,
            int leafSplits,
            int internalSplits,
            int newRootsCreated,
            long rootPageIdBefore,
            long rootPageIdAfter,
            String order,
            long elapsedMillis) {}
}
