package com.nimeshkmr.storage.explorer;

import com.nimeshkmr.storage.BPlusTree;
import com.nimeshkmr.storage.DiskManager;
import com.nimeshkmr.storage.InsertReport;
import com.nimeshkmr.storage.LogRecord;
import com.nimeshkmr.storage.WriteAheadLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Controlled recovery demonstrations that run entirely in a fresh operating-system temp directory.
 * The databases under the explorer's data directory are never touched.
 *
 * <p>Each run: (1) builds a small tree with real engine inserts, (2) performs one more real insert
 * and lets the engine close normally, (3) edits the closed files to recreate one specific on-disk
 * state, (4) shows what the files contain before recovery, (5) opens them with the engine's normal
 * BPlusTree#initialize (which runs RecoveryManager), and (6) reports what actually changed.
 *
 * <p>This is a file-level simulation. It does not kill the JVM, drop OS caches or cut power; it
 * recreates the on-disk result of a crash at one chosen point with ordinary file operations.
 */
final class RecoveryDemo {

    static final int LEAF_CAPACITY = 4;
    static final int INTERNAL_CAPACITY = 4;
    /** Keys 1..BASE_KEYS are inserted first; key TARGET_KEY is then inserted by the insert the "crash" interrupts. */
    static final int BASE_KEYS = 10;
    static final int TARGET_KEY = 11;

    private static final int TORN_BYTES_KEPT = 9;

    private record Scenario(String id, String title, String summary, String crashStep,
            boolean restoreDatabase, int walMode, boolean expectTargetAfterRecovery) {}

    private static final int WAL_KEEP = 0;
    private static final int WAL_DROP_COMMIT = 1;
    private static final int WAL_TEAR_COMMIT = 2;

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("clean-reopen",
                    "Clean reopen (control)",
                    "Both files are left exactly as the engine wrote them after a completed insert, then reopened.",
                    "No crash is simulated.",
                    false, WAL_KEEP, true),
            new Scenario("commit-logged-pages-lost",
                    "Commit record logged, page writes lost",
                    "The WAL holds the page images and the commit record, but the database file is rolled back "
                            + "to its bytes from before the insert, as if the page writes never reached it.",
                    "Overwrites the database file with its pre-insert bytes. The WAL is left intact.",
                    true, WAL_KEEP, true),
            new Scenario("commit-record-missing",
                    "Page images logged, commit record missing",
                    "The WAL holds the page images but its final commit record is cut off, and the database file "
                            + "is rolled back to its pre-insert bytes.",
                    "Truncates the WAL just before the commit record and overwrites the database file with its "
                            + "pre-insert bytes.",
                    true, WAL_DROP_COMMIT, false),
            new Scenario("commit-record-torn",
                    "Commit record only partly written",
                    "Only the first " + TORN_BYTES_KEPT + " of the commit record's "
                            + Inspector.COMMIT_RECORD_BYTES + " bytes remain in the WAL, and the database file is "
                            + "rolled back to its pre-insert bytes.",
                    "Truncates the WAL " + TORN_BYTES_KEPT + " bytes into the commit record and overwrites the "
                            + "database file with its pre-insert bytes.",
                    true, WAL_TEAR_COMMIT, false));

    private RecoveryDemo() {}

    static List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scenario s : SCENARIOS) {
            out.add(Inspector.map("id", s.id(), "title", s.title(), "summary", s.summary(),
                    "crashStep", s.crashStep(), "expectTargetKeyAfterRecovery", s.expectTargetAfterRecovery()));
        }
        return out;
    }

    static Map<String, Object> run(String scenarioId) throws IOException {
        Scenario sc = SCENARIOS.stream().filter(s -> s.id().equals(scenarioId)).findFirst()
                .orElseThrow(() -> ApiException.invalid("Unknown scenario '" + scenarioId + "'"));

        Path dir = Files.createTempDirectory("storage-explorer-recovery-");
        try {
            return execute(sc, dir);
        } finally {
            deleteTree(dir);
        }
    }

    private static Map<String, Object> execute(Scenario sc, Path dir) throws IOException {
        Path db = dir.resolve("demo.db");
        Path wal = dir.resolve("demo.wal");

        // 1. Build the baseline tree with real, WAL-protected inserts.
        try (DiskManager dm = new DiskManager(db); WriteAheadLog w = new WriteAheadLog(wal)) {
            BPlusTree t = new BPlusTree(dm, w, LEAF_CAPACITY, INTERNAL_CAPACITY);
            t.initialize();
            for (int k = 1; k <= BASE_KEYS; k++) t.insert(k, k * 10);
        }

        // 2. Reopen, remember the file as it is now, then run the insert that the "crash" will interrupt.
        byte[] dbBeforeInsert;
        long walBytesBeforeInsert;
        InsertReport report;
        try (DiskManager dm = new DiskManager(db); WriteAheadLog w = new WriteAheadLog(wal)) {
            BPlusTree t = new BPlusTree(dm, w, LEAF_CAPACITY, INTERNAL_CAPACITY);
            t.initialize();
            dbBeforeInsert = Files.readAllBytes(db);
            walBytesBeforeInsert = w.getLogSize();
            report = t.insertWithReport(TARGET_KEY, TARGET_KEY * 10);
        }
        long walBytesAfterInsert = Files.size(wal);
        long dbBytesAfterInsert = Files.size(db);

        // 3. Recreate the chosen on-disk state with ordinary file operations (engine is closed).
        long commitOffset = walBytesAfterInsert - Inspector.COMMIT_RECORD_BYTES;
        verifyTrailingCommit(wal, commitOffset, report.transactionID());
        if (sc.walMode() == WAL_DROP_COMMIT) {
            truncate(wal, commitOffset);
        } else if (sc.walMode() == WAL_TEAR_COMMIT) {
            truncate(wal, commitOffset + TORN_BYTES_KEPT);
        }
        if (sc.restoreDatabase()) {
            Files.write(db, dbBeforeInsert);
        }
        long walBytesAtCrash = Files.size(wal);
        long dbBytesAtCrash = Files.size(db);

        // 4a. What the WAL contains, as the engine would parse it. Done on a COPY because opening a
        //     WriteAheadLog trims an incomplete trailing record, and we want to show that separately.
        Path walCopy = dir.resolve("wal-inspect-copy.wal");
        Files.copy(wal, walCopy, StandardCopyOption.REPLACE_EXISTING);
        long copyBytesBeforeOpen = Files.size(walCopy);
        Dto.WalView walBefore;
        long copyBytesAfterOpen;
        try (WriteAheadLog w = new WriteAheadLog(walCopy)) {
            copyBytesAfterOpen = w.getLogSize();
            walBefore = Inspector.walView(w, 0, ExplorerService.MAX_WAL_PAGE);
        }

        // 4b. What the database file alone says (no WAL attached, so no recovery runs).
        String fileHashBefore = sha256(Files.readAllBytes(db));
        Integer valueBefore;
        Dto.TreeSnapshot treeBefore;
        try (DiskManager dm = new DiskManager(db)) {
            BPlusTree t = new BPlusTree(dm, null, LEAF_CAPACITY, INTERNAL_CAPACITY);
            t.initialize();
            valueBefore = t.search(TARGET_KEY);
            treeBefore = t.readConsistently((d, root) -> Inspector.snapshot(d, root, LEAF_CAPACITY, INTERNAL_CAPACITY));
        }
        boolean observationLeftFileUntouched = fileHashBefore.equals(sha256(Files.readAllBytes(db)));
        List<String> digestsBefore = pageDigests(db);

        // 5. Normal open: BPlusTree#initialize runs RecoveryManager over the WAL.
        long walBytesBeforeRealOpen = Files.size(wal);
        long walBytesAfterWalOpen;
        Integer valueAfter;
        Dto.TreeSnapshot treeAfter;
        Dto.WalView walAfter;
        long rootAfter;
        try (DiskManager dm = new DiskManager(db); WriteAheadLog w = new WriteAheadLog(wal)) {
            walBytesAfterWalOpen = w.getLogSize();
            BPlusTree t = new BPlusTree(dm, w, LEAF_CAPACITY, INTERNAL_CAPACITY);
            t.initialize();
            valueAfter = t.search(TARGET_KEY);
            rootAfter = t.getRootPageID();
            treeAfter = t.readConsistently((d, root) -> Inspector.snapshot(d, root, LEAF_CAPACITY, INTERNAL_CAPACITY));
            walAfter = Inspector.walView(w, 0, ExplorerService.MAX_WAL_PAGE);
        }
        List<String> digestsAfter = pageDigests(db);

        List<Long> changed = new ArrayList<>();
        List<Long> appended = new ArrayList<>();
        for (int i = 0; i < digestsAfter.size(); i++) {
            if (i >= digestsBefore.size()) appended.add((long) i);
            else if (!digestsBefore.get(i).equals(digestsAfter.get(i))) changed.add((long) i);
        }

        // 6. Replaying is repeated on every open, so a second open must change nothing.
        List<String> digestsSecond;
        try (DiskManager dm = new DiskManager(db); WriteAheadLog w = new WriteAheadLog(wal)) {
            new BPlusTree(dm, w, LEAF_CAPACITY, INTERNAL_CAPACITY).initialize();
        }
        digestsSecond = pageDigests(db);
        boolean secondOpenIdentical = digestsSecond.equals(digestsAfter);

        boolean observedPresent = valueAfter != null && valueAfter == TARGET_KEY * 10;
        boolean expected = sc.expectTargetAfterRecovery();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", Inspector.map("id", sc.id(), "title", sc.title(), "summary", sc.summary()));
        result.put("simulation", Inspector.map(
                "whatItDoes", List.of(
                        "Creates a database and WAL in a new temporary directory (demo fan-out "
                                + LEAF_CAPACITY + "/" + INTERNAL_CAPACITY + ") and runs " + BASE_KEYS
                                + " real inserts through the engine.",
                        "Reopens it, saves the database file's bytes, then runs one more real insert (key "
                                + TARGET_KEY + ") and closes the engine normally.",
                        sc.crashStep(),
                        "Reopens with the normal BPlusTree.initialize(), which runs RecoveryManager over the WAL.",
                        "Reopens a second time to check that replaying the log again changes nothing."),
                "whatItDoesNot", List.of(
                        "It does not kill the JVM, drop operating-system write buffers or cut power. The engine has "
                                + "already closed both files when they are edited.",
                        "It does not touch any database in the explorer's data directory.",
                        "It shows one crash point per scenario, not every possible one.")));
        result.put("baseline", Inspector.map("keysInserted", BASE_KEYS, "targetKey", TARGET_KEY,
                "targetInsertReport", report,
                "walBytesBeforeInsert", walBytesBeforeInsert, "walBytesAfterInsert", walBytesAfterInsert,
                "databaseBytesBeforeInsert", dbBeforeInsert.length, "databaseBytesAfterInsert", dbBytesAfterInsert));
        result.put("stateAtCrash", Inspector.map("walBytes", walBytesAtCrash, "databaseBytes", dbBytesAtCrash,
                "databaseRestoredToPreInsertBytes", sc.restoreDatabase()));
        result.put("beforeRecovery", Inspector.map(
                "wal", walBefore,
                "walBytesOnDisk", copyBytesBeforeOpen,
                "walBytesDiscardedByWalOpen", copyBytesBeforeOpen - copyBytesAfterOpen,
                "databaseTree", treeBefore,
                "targetKeyValueInDatabaseFile", valueBefore,
                "observationLeftDatabaseFileUntouched", observationLeftFileUntouched));
        result.put("recovery", Inspector.map(
                "walBytesBeforeOpen", walBytesBeforeRealOpen,
                "walBytesAfterWalOpen", walBytesAfterWalOpen,
                "pagesChangedByRecovery", changed,
                "pagesAppendedByRecovery", appended,
                "databasePagesBefore", digestsBefore.size(),
                "databasePagesAfter", digestsAfter.size(),
                "secondOpenChangedNothing", secondOpenIdentical,
                "rootPageIdAfter", rootAfter,
                "rule", "RecoveryManager replays every page image whose transaction has a COMMIT record in the "
                        + "log and ignores the rest. The changed pages above were measured by comparing page "
                        + "bytes before and after the open, not derived from that rule."));
        result.put("afterRecovery", Inspector.map("wal", walAfter, "databaseTree", treeAfter,
                "targetKeyValue", valueAfter));
        result.put("outcome", Inspector.map(
                "expectedTargetKeyPresent", expected,
                "observedTargetKeyPresent", observedPresent,
                "matchesExpectation", expected == observedPresent && secondOpenIdentical
                        && treeAfter.invariantViolations().isEmpty()));
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private static void verifyTrailingCommit(Path wal, long commitOffset, long txnId) throws IOException {
        if (commitOffset < 0) throw new IOException("WAL is shorter than one commit record");
        ByteBuffer b = ByteBuffer.allocate(Inspector.COMMIT_RECORD_BYTES);
        try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.READ)) {
            while (b.hasRemaining()) {
                if (ch.read(b, commitOffset + b.position()) < 0) throw new IOException("Unexpected end of WAL");
            }
        }
        b.flip();
        int len = b.getInt();
        byte type = b.get();
        long tx = b.getLong();
        if (len != 13 || type != LogRecord.COMMIT || tx != txnId) {
            throw new IOException("Expected the last WAL record to be the commit record of transaction "
                    + txnId + " but found length=" + len + " type=" + type + " txn=" + tx);
        }
    }

    private static void truncate(Path file, long size) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(size);
            ch.force(true);
        }
    }

    private static List<String> pageDigests(Path db) throws IOException {
        List<String> out = new ArrayList<>();
        try (DiskManager dm = new DiskManager(db)) {
            long n = dm.getNumPages();
            for (long p = 0; p < n; p++) {
                out.add(sha256(dm.readPage(p).getByteBuffer().array()));
            }
        }
        return out;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
