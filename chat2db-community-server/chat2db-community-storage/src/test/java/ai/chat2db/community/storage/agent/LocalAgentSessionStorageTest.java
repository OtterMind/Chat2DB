package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeBinding;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeId;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.AgentSessionStatus;
import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.exception.storage.StorageException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentSessionStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesV2HistoryUnderTheEnvironmentStorageDirectory() {
        Path environmentRoot = temporaryDirectory.resolve(".chat2db/release");

        assertEquals(
                environmentRoot.resolve("storage/ai-chat-history-v2"),
                AgentV2StoragePaths.resolveRoot(environmentRoot));
    }

    @Test
    void readBeforeFirstV2SessionDoesNotCreateStorage() {
        AgentV2StoragePaths paths = paths();
        LocalAgentSessionStorage storage = new LocalAgentSessionStorage(paths, new StorageFileUtils());

        assertTrue(storage.listByUserId(1L).isEmpty());
        assertNull(storage.get("missing", 1L));
        assertFalse(Files.exists(paths.root()));
    }

    @Test
    void createsReadsListsAndUpdatesV2Sessions() {
        AgentV2StoragePaths paths = paths();
        LocalAgentSessionStorage storage = new LocalAgentSessionStorage(paths, new StorageFileUtils());
        AgentSession created = session("session-one", 1L, "Initial", AgentSessionStatus.CREATED, 0);

        storage.create(created);
        AgentSession updated = session("session-one", 1L, "Updated", AgentSessionStatus.READY, 1);
        assertTrue(storage.compareAndSet(updated, AgentSessionStatus.CREATED));

        assertEquals(updated, storage.get("session-one", 1L));
        assertEquals(List.of(updated), storage.listByUserId(1L));
        assertEquals(2, sessionSchemaVersion(paths));
    }

    @Test
    void hidesOtherUsersSessionsAndRejectsOwnerChanges() {
        AgentV2StoragePaths paths = paths();
        LocalAgentSessionStorage storage = new LocalAgentSessionStorage(paths, new StorageFileUtils());
        storage.create(session("owned-session", 1L, "Owned", AgentSessionStatus.CREATED, 0));

        assertNull(storage.get("owned-session", 2L));
        assertTrue(storage.listByUserId(2L).isEmpty());
        assertThrows(StorageException.class, () -> storage.compareAndSet(
                session("owned-session", 2L, "Changed owner", AgentSessionStatus.READY, 1),
                AgentSessionStatus.CREATED));
    }

    @Test
    void doesNotReadOrModifyV1History() throws IOException {
        Path v1History = temporaryDirectory.resolve(".chat2db/ai-chat-history/sessions-1.json");
        Files.createDirectories(v1History.getParent());
        Files.writeString(v1History, "v1-history", StandardCharsets.UTF_8);
        LocalAgentSessionStorage storage = new LocalAgentSessionStorage(paths(), new StorageFileUtils());

        storage.create(session("v2-session", 1L, "V2", AgentSessionStatus.CREATED, 0));

        assertEquals("v1-history", Files.readString(v1History, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnsupportedStorageSchema() throws IOException {
        AgentV2StoragePaths paths = paths();
        Files.createDirectories(paths.sessionsDirectory());
        Files.writeString(paths.schemaFile(), "{\"schemaVersion\":1}", StandardCharsets.UTF_8);
        LocalAgentSessionStorage storage = new LocalAgentSessionStorage(paths, new StorageFileUtils());

        assertThrows(StorageException.class, () -> storage.listByUserId(1L));
    }

    @Test
    void rejectsInvalidIdsAndSymbolicSessionDirectories() throws IOException {
        AgentV2StoragePaths paths = paths();
        LocalAgentSessionStorage storage = new LocalAgentSessionStorage(paths, new StorageFileUtils());
        assertThrows(IllegalArgumentException.class, () -> storage.get("../v1", 1L));

        Files.createDirectories(paths.sessionsDirectory());
        Files.writeString(paths.schemaFile(), "{\"schemaVersion\":2}", StandardCharsets.UTF_8);
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        try {
            Files.createSymbolicLink(paths.sessionDirectory("linked-session"), outside);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
            return;
        }

        assertThrows(StorageException.class, () -> storage.create(
                session("linked-session", 1L, "Linked", AgentSessionStatus.CREATED, 0)));
    }

    @Test
    void failedAtomicUpdateKeepsThePreviousSession() throws IOException {
        AgentV2StoragePaths paths = paths();
        LocalAgentSessionStorage healthyStorage = new LocalAgentSessionStorage(paths, new StorageFileUtils());
        AgentSession original = session("atomic-session", 1L, "Original", AgentSessionStatus.CREATED, 0);
        healthyStorage.create(original);
        StorageFileUtils failingFileUtils = new StorageFileUtils() {
            @Override
            protected void replaceStorageFile(Path temporary, Path target) throws IOException {
                throw new IOException("simulated replacement failure");
            }
        };
        LocalAgentSessionStorage failingStorage = new LocalAgentSessionStorage(paths, failingFileUtils);

        assertThrows(StorageException.class, () -> failingStorage.compareAndSet(
                session("atomic-session", 1L, "Replacement", AgentSessionStatus.READY, 1),
                AgentSessionStatus.CREATED));

        assertEquals(original, healthyStorage.get("atomic-session", 1L));
        try (var files = Files.list(paths.sessionDirectory("atomic-session"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void failedCreateDoesNotLeaveAnIncompleteSessionDirectory() {
        AgentV2StoragePaths paths = paths();
        StorageFileUtils failingFileUtils = new StorageFileUtils() {
            @Override
            protected void replaceStorageFile(Path temporary, Path target) throws IOException {
                if (target.getFileName().toString().equals("session.json")) {
                    throw new IOException("simulated session create failure");
                }
                super.replaceStorageFile(temporary, target);
            }
        };
        LocalAgentSessionStorage failingStorage = new LocalAgentSessionStorage(paths, failingFileUtils);

        assertThrows(StorageException.class, () -> failingStorage.create(
                session("failed-session", 1L, "Failed", AgentSessionStatus.CREATED, 0)));

        assertFalse(Files.exists(paths.sessionDirectory("failed-session")));
        assertTrue(failingStorage.listByUserId(1L).isEmpty());
    }

    private AgentV2StoragePaths paths() {
        return new AgentV2StoragePaths(
                temporaryDirectory.resolve(".chat2db/release/storage/ai-chat-history-v2"));
    }

    private int sessionSchemaVersion(AgentV2StoragePaths paths) {
        return com.alibaba.fastjson2.JSON.parseObject(read(paths.schemaFile())).getIntValue("schemaVersion");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private AgentSession session(
            String id,
            Long userId,
            String title,
            AgentSessionStatus status,
            long lastEventSequence) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 8, 20, 0);
        return new AgentSession(
                AgentSession.SCHEMA_VERSION,
                id,
                userId,
                "default",
                1,
                new AgentRuntimeBinding(
                        new AgentRuntimeId("pi"), "0.85.1", "jsonl-rpc", id, null, 1),
                status,
                title,
                lastEventSequence,
                createdAt,
                createdAt.plusMinutes(lastEventSequence));
    }
}
