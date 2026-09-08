package ai.chat2db.community.domain.core.impl.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generational resume journal: snapshots are checksummed and atomically rotated, the committed
 * pointer stays one commit behind the newest generation, torn files fail validation, and recovery
 * falls back down the chain instead of trusting a damaged candidate.
 */
class TaskResumeJournalTest {

    @TempDir
    Path tempDir;

    private File dir() {
        return tempDir.resolve("journal").toFile();
    }

    @Test
    void snapshotRoundTripsThroughRecovery() {
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(dir(), Map.of());
        journal.snapshot(100);
        Optional<TaskResumeJournal.Snapshot> newest = TaskResumeJournal.recoverNewest(dir());
        assertTrue(newest.isPresent());
        assertEquals(100, newest.get().rowsDone());
        assertEquals(1, newest.get().seq());
    }

    @Test
    void committedFallbackStaysOneCommitBehind() {
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(dir(), Map.of());
        journal.snapshot(100);
        journal.snapshot(200);
        assertEquals(200, TaskResumeJournal.recoverNewest(dir()).orElseThrow().rowsDone());
        assertEquals(100, TaskResumeJournal.recoverCommitted(dir()).orElseThrow().rowsDone(),
                "the fallback must be the state before the last successful commit");
    }

    @Test
    void tornNewestGenerationFallsBackToCommitted() throws Exception {
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(dir(), Map.of());
        journal.snapshot(100);
        journal.snapshot(200);
        Files.writeString(dir().toPath().resolve("gen-2.json"), "{torn", StandardCharsets.UTF_8);
        assertEquals(100, TaskResumeJournal.recoverNewest(dir()).orElseThrow().rowsDone(),
                "a checksum-invalid generation must be skipped, not trusted");
    }

    @Test
    void journalTailCarriesTheFreshestWatermark() {
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(dir(), Map.of());
        journal.progress("IMPORTING", 120);
        journal.progress("IMPORTING", 150);
        assertEquals(150, TaskResumeJournal.recoverTail(dir()).orElseThrow().rowsDone());
    }

    @Test
    void tornTailRecordIsIgnored() throws Exception {
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(dir(), Map.of());
        journal.progress("IMPORTING", 150);
        Files.writeString(dir().toPath().resolve("progress.ndjson"),
                "{\"kind\":\"progress\",\"rowsDone\":999,\"ts\":1,\"checksum\":\"deadbeef\"}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(150, TaskResumeJournal.recoverTail(dir()).orElseThrow().rowsDone(),
                "a checksum-invalid tail record must be ignored");
    }

    @Test
    void staleIdentityIsCarriedForCallerSideValidation() {
        Map<String, Object> identity = Map.of("sourceLength", 10L, "sourceLastModified", 20L);
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(dir(), identity);
        journal.snapshot(100);
        TaskResumeJournal.Snapshot snapshot = TaskResumeJournal.recoverNewest(dir()).orElseThrow();
        // fastjson2 renders small JSON numbers as Integer; compare numerically
        assertEquals(10L, ((Number) snapshot.identity().get("sourceLength")).longValue());
        assertEquals(20L, ((Number) snapshot.identity().get("sourceLastModified")).longValue());
    }

    @Test
    void cleanupRemovesEverything() {
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(dir(), Map.of());
        journal.snapshot(100);
        assertTrue(dir().exists());
        journal.cleanup();
        assertFalse(dir().exists(), "a clean run leaves no journal behind");
        assertTrue(TaskResumeJournal.recoverNewest(dir()).isEmpty());
    }

    @Test
    void preserveClosesWriterWithoutDeletingRecoveryState() throws Exception {
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(dir(), Map.of());
        journal.progress("IMPORTING", 75);

        journal.preserve();

        assertTrue(dir().exists());
        assertEquals(75, TaskResumeJournal.recoverTail(dir()).orElseThrow().rowsDone());
        Files.move(dir().toPath().resolve("progress.ndjson"),
                dir().toPath().resolve("progress.closed.ndjson"));
    }
}
