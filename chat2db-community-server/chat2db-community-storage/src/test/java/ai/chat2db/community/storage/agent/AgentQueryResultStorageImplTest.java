package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.chart.DbAgentQueryResult;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.QueryColumn;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.QueryData;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.Scope;
import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AgentQueryResultStorageImplTest {
    @TempDir Path directory;

    @Test
    void persistsExactValuesIsolatesSessionsAndDeletesSnapshotsWithTheSession() throws Exception {
        var paths = new AgentV2StoragePaths(directory.resolve("history"));
        var files = new StorageFileUtils();
        var sessions = new LocalAgentSessionStorage(paths, files);
        sessions.create(session("session"));
        sessions.create(session("other"));
        var outputs = new AgentOutputStorageImpl(paths, files, sessions, 4 * 1024 * 1024, 8 * 1024 * 1024, 16 * 1024 * 1024);
        var storage = new AgentQueryResultStorageImpl(paths, files, sessions, outputs);
        var data = new QueryData(List.of(new QueryColumn("value", "DECIMAL")),
                List.of(List.of("9007199254740993.1200"), Arrays.asList((String) null)), "database-text", 1L, List.of(), null);
        var snapshot = new DbAgentQueryResult("result", "session", "run", "SELECT value", new Scope("1", "MYSQL", "db", null), data, null, List.of());
        storage.create(snapshot, 1L);
        assertEquals(snapshot, new AgentQueryResultStorageImpl(paths, files, sessions, outputs).get("session", "result", 1L));
        assertNotNull(storage.output("session", "result", 1L));
        assertTrue(Files.size(paths.resourceFile("session", "query-results", "result")) < 256);
        assertNull(storage.get("session", "result", 2L));
        assertNull(storage.get("other", "result", 1L));
        assertThrows(IllegalArgumentException.class, () -> storage.get("session", "../result", 1L));
        sessions.delete("session", 1L);
        assertNull(storage.get("session", "result", 1L));
        assertFalse(Files.exists(paths.sessionDirectory("session")));
    }

    @Test
    void quotaRetainsThePartialFileReferenceButNeverReturnsItAsACompleteSnapshot() {
        var paths = new AgentV2StoragePaths(directory.resolve("quota"));
        var files = new StorageFileUtils();
        var sessions = new LocalAgentSessionStorage(paths, files);
        sessions.create(session("session"));
        var outputs = new AgentOutputStorageImpl(paths, files, sessions, 512, 512, 512);
        var storage = new AgentQueryResultStorageImpl(paths, files, sessions, outputs);
        var data = new QueryData(List.of(new QueryColumn("body", "TEXT")), List.of(List.of("x".repeat(10000))),
                "database-text", 1L, List.of(), null);
        storage.create(new DbAgentQueryResult("result", "session", "run", "SELECT body", new Scope("1", "MYSQL", "db", null), data, null, List.of()), 1L);
        var reference = storage.output("session", "result", 1L);
        assertEquals("file", reference.mode());
        assertFalse(reference.complete());
        assertEquals(512, reference.sizeBytes());
        assertNull(storage.get("session", "result", 1L));
        assertFalse(outputs.read("session", 1L, reference.artifactId(), null, null, null).content().isEmpty());
    }

    @Test
    void sourceCaptureWarningRemainsIncompleteAfterRestartAndDirectArtifactLookup() {
        var paths = new AgentV2StoragePaths(directory.resolve("source-partial"));
        var files = new StorageFileUtils();
        var sessions = new LocalAgentSessionStorage(paths, files);
        sessions.create(session("session"));
        var outputs = new AgentOutputStorageImpl(paths, files, sessions, 16384, 32768, 65536);
        var storage = new AgentQueryResultStorageImpl(paths, files, sessions, outputs);
        String reason = "CAPTURE_BUDGET_EXCEEDED: V2 query capture memory limit reached";
        var warning = new ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.CellWarning(
                0, 0, reason, null, 7L);
        var data = new QueryData(List.of(new QueryColumn("body", "TEXT")), List.of(List.of("partial")),
                "database-text", 1L, List.of(warning), null);
        storage.create(new DbAgentQueryResult("result", "session", "run", "SELECT body",
                new Scope("1", "MYSQL", "db", null), data, null, List.of()), 1L);
        var saved = storage.output("session", "result", 1L);
        var restarted = new AgentOutputStorageImpl(paths, files, sessions, 16384, 32768, 65536);
        var direct = restarted.reference("session", 1L, saved.artifactId());
        assertFalse(saved.complete());
        assertFalse(direct.complete());
        assertTrue(direct.warning().contains(reason));
        assertNull(new AgentQueryResultStorageImpl(paths, files, sessions, restarted).get("session", "result", 1L));
        assertTrue(restarted.read("session", 1L, direct.artifactId(), null, null, null).content().contains("partial"));
        var context = new ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext(
                "session", "run", "interrupted", 1L, ignored -> { }, () -> true);
        var interrupted = restarted.save(context, "text", stream -> {
            stream.write("partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw new java.io.IOException("disk interrupted");
        }, false, reason);
        assertFalse(interrupted.complete());
        assertTrue(interrupted.warning().contains(reason));
        assertTrue(interrupted.warning().contains("disk interrupted"));
    }

    private AgentSession session(String id) {
        var now = LocalDateTime.now();
        return new AgentSession(2, id, 1L,
                new AgentDefinition("default", "Default", null, "prompt", AgentRuntimeType.PI, "model", 1),
                new AgentRuntimeBinding(AgentRuntimeType.PI, "0.85.1", "rpc", id, null, 1), AgentSessionStatus.READY,
                "Conversation", 0, now, now);
    }
}
