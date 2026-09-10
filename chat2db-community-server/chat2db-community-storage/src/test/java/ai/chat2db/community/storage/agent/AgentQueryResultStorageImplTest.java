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
    void persistsExactValuesIsolatesSessionsAndDeletesSnapshotsWithTheSession() {
        var paths = new AgentV2StoragePaths(directory.resolve("history"));
        var files = new StorageFileUtils();
        var sessions = new LocalAgentSessionStorage(paths, files);
        sessions.create(session("session"));
        sessions.create(session("other"));
        var storage = new AgentQueryResultStorageImpl(paths, files, sessions);
        var data = new QueryData(List.of(new QueryColumn("value", "DECIMAL")),
                List.of(List.of("9007199254740993.1200"), Arrays.asList((String) null)), "database-text", 1L, List.of(), null);
        var snapshot = new DbAgentQueryResult("result", "session", "run", "SELECT value", new Scope("1", "MYSQL", "db", null), data, null, List.of());
        storage.create(snapshot, 1L);
        assertEquals(snapshot, new AgentQueryResultStorageImpl(paths, files, sessions).get("session", "result", 1L));
        assertNull(storage.get("session", "result", 2L));
        assertNull(storage.get("other", "result", 1L));
        assertThrows(IllegalArgumentException.class, () -> storage.get("session", "../result", 1L));
        sessions.delete("session", 1L);
        assertNull(storage.get("session", "result", 1L));
        assertFalse(Files.exists(paths.sessionDirectory("session")));
    }

    private AgentSession session(String id) {
        var now = LocalDateTime.now();
        return new AgentSession(2, id, 1L,
                new AgentDefinition("default", "Default", null, "prompt", AgentRuntimeType.PI, "model", 1),
                new AgentRuntimeBinding(AgentRuntimeType.PI, "0.85.1", "rpc", id, null, 1), AgentSessionStatus.READY,
                "Conversation", 0, now, now);
    }
}
