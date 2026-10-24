package com.nimeshkmr.storage.explorer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryDemoTest {

    @TempDir
    Path tempDir;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String k) {
        return (Map<String, Object>) m.get(k);
    }

    private static Map<String, Object> run(String id) throws IOException {
        return RecoveryDemo.run(id);
    }

    @Test
    void everyScenarioMatchesItsExpectationAndIsIdempotent() throws IOException {
        for (Map<String, Object> s : RecoveryDemo.describeAll()) {
            String id = (String) s.get("id");
            Map<String, Object> r = run(id);
            Map<String, Object> outcome = sub(r, "outcome");
            assertEquals(Boolean.TRUE, outcome.get("matchesExpectation"), id + ": " + outcome);
            assertEquals(s.get("expectTargetKeyAfterRecovery"), outcome.get("observedTargetKeyPresent"), id);
            assertEquals(Boolean.TRUE, sub(r, "recovery").get("secondOpenChangedNothing"), id);
            assertEquals(Boolean.TRUE, sub(r, "beforeRecovery").get("observationLeftDatabaseFileUntouched"), id);
        }
    }

    @Test
    void cleanReopenChangesNothing() throws IOException {
        Map<String, Object> r = run("clean-reopen");
        assertEquals(List.of(), sub(r, "recovery").get("pagesChangedByRecovery"));
        assertEquals(List.of(), sub(r, "recovery").get("pagesAppendedByRecovery"));
        assertEquals(Integer.valueOf(110), sub(r, "beforeRecovery").get("targetKeyValueInDatabaseFile"));
    }

    @Test
    void committedTransactionIsReplayedIntoARolledBackDatabase() throws IOException {
        Map<String, Object> r = run("commit-logged-pages-lost");
        assertNull(sub(r, "beforeRecovery").get("targetKeyValueInDatabaseFile"),
                "before recovery the database file must not contain the key");
        assertEquals(110, sub(r, "afterRecovery").get("targetKeyValue"));
        List<?> changed = (List<?>) sub(r, "recovery").get("pagesChangedByRecovery");
        List<?> appended = (List<?>) sub(r, "recovery").get("pagesAppendedByRecovery");
        assertFalse(changed.isEmpty() && appended.isEmpty(), "recovery must have rewritten something");
        Dto.WalView before = (Dto.WalView) sub(r, "beforeRecovery").get("wal");
        assertEquals(0, before.summary().transactionsWithoutCommitRecord().size());
    }

    @Test
    void transactionWithoutCommitRecordIsIgnored() throws IOException {
        Map<String, Object> r = run("commit-record-missing");
        Dto.WalView before = (Dto.WalView) sub(r, "beforeRecovery").get("wal");
        assertEquals(1, before.summary().transactionsWithoutCommitRecord().size());
        assertNull(sub(r, "afterRecovery").get("targetKeyValue"));
        assertEquals(0L, ((Number) sub(r, "beforeRecovery").get("walBytesDiscardedByWalOpen")).longValue());
    }

    @Test
    void tornCommitRecordIsTrimmedByTheWalAndTheTransactionIsIgnored() throws IOException {
        Map<String, Object> r = run("commit-record-torn");
        assertEquals(9L, ((Number) sub(r, "beforeRecovery").get("walBytesDiscardedByWalOpen")).longValue(),
                "opening the WAL should trim exactly the 9 torn bytes");
        assertNull(sub(r, "afterRecovery").get("targetKeyValue"));
    }

    @Test
    void demoLeavesNoFilesBehindAndNeverTouchesTheDataDirectory() throws IOException {
        Path data = tempDir.resolve("data");
        Files.createDirectories(data);
        try (ExplorerService s = new ExplorerService(data)) {
            s.create("keep", 4, 4);
            s.insert("keep", 1, 1);
            byte[] dbBefore = Files.readAllBytes(data.resolve("keep.db"));
            byte[] walBefore = Files.readAllBytes(data.resolve("keep.wal"));
            for (Map<String, Object> sc : s.recoveryScenarios()) s.runRecoveryDemo((String) sc.get("id"));
            assertArrayEquals(dbBefore, Files.readAllBytes(data.resolve("keep.db")));
            assertArrayEquals(walBefore, Files.readAllBytes(data.resolve("keep.wal")));
            assertEquals(1L, s.tree("keep").keyCount());
        }
    }

    @Test
    void unknownScenarioIsRejected() {
        ApiException e = assertThrows(ApiException.class, () -> RecoveryDemo.run("nope"));
        assertEquals(400, e.status());
    }
}
