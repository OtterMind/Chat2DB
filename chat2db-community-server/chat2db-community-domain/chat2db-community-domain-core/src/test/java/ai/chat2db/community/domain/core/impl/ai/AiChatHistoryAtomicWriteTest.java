package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.tools.exception.BusinessException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatHistoryAtomicWriteTest {

    private static final long OWNER_ID = 42L;

    @TempDir
    Path tempDirectory;

    @Test
    void failedMessageSerializationKeepsPreviousHistoryReadable() throws Exception {
        ToggleSerializer<AiChatHistoryServiceImpl.MessagesFile> serializer = new ToggleSerializer<>(
                "messages", AiChatHistoryServiceImpl.MessagesFile::getMessages);
        ObjectMapper objectMapper = objectMapper(AiChatHistoryServiceImpl.MessagesFile.class, serializer);
        AiChatHistoryServiceImpl service = new AiChatHistoryServiceImpl(objectMapper, tempDirectory);
        AiChatSession session = service.createSession(OWNER_ID, "atomic messages");
        service.addMessage(addRequest(session.getId(), "before"));
        Path messagesPath = tempDirectory.resolve(session.getId() + ".json");
        byte[] original = Files.readAllBytes(messagesPath);
        serializer.failWrites();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.addMessage(addRequest(session.getId(), "after")));

        assertEquals("ai.chat.history.persistMessagesFailed", exception.getCode());
        assertTrue(serializer.failedAfterFlush());
        assertAll(
                () -> assertArrayEquals(original, Files.readAllBytes(messagesPath)),
                () -> assertDoesNotThrow(() -> objectMapper.readTree(messagesPath.toFile())),
                () -> assertEquals(List.of("before"), service.getMessages(session.getId(), OWNER_ID).stream()
                        .map(AiChatMessage::getContent).toList()),
                this::assertNoTemporaryFiles
        );
    }

    @Test
    void failedSessionSerializationKeepsPreviousSessionsReadable() throws Exception {
        ToggleSerializer<AiChatHistoryServiceImpl.SessionsFile> serializer = new ToggleSerializer<>(
                "sessions", AiChatHistoryServiceImpl.SessionsFile::getSessions);
        ObjectMapper objectMapper = objectMapper(AiChatHistoryServiceImpl.SessionsFile.class, serializer);
        AiChatHistoryServiceImpl service = new AiChatHistoryServiceImpl(objectMapper, tempDirectory);
        service.createSession(OWNER_ID, "before");
        Path sessionsPath = tempDirectory.resolve("sessions-" + OWNER_ID + ".json");
        byte[] original = Files.readAllBytes(sessionsPath);
        serializer.failWrites();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createSession(OWNER_ID, "after"));

        assertEquals("ai.chat.history.persistSessionsFailed", exception.getCode());
        assertTrue(serializer.failedAfterFlush());
        assertAll(
                () -> assertArrayEquals(original, Files.readAllBytes(sessionsPath)),
                () -> assertDoesNotThrow(() -> objectMapper.readTree(sessionsPath.toFile())),
                () -> assertEquals(List.of("before"), service.listSessions(OWNER_ID).stream()
                        .map(AiChatSession::getTitle).toList()),
                this::assertNoTemporaryFiles
        );
    }

    @Test
    void invalidSessionIdCannotCreateMessageFile() throws Exception {
        String sessionId = "not-a-uuid";
        Path historyDirectory = tempDirectory.resolve("invalid-history");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        writeSessionIndex(objectMapper, historyDirectory, sessionId);
        AiChatHistoryServiceImpl service = new AiChatHistoryServiceImpl(objectMapper, historyDirectory);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.addMessage(addRequest(sessionId, "blocked")));

        assertEquals("ai.chat.history.sessionNotOwned", exception.getCode());
        assertTrue(Files.notExists(historyDirectory.resolve(sessionId + ".json")));
    }

    @Test
    void traversalSessionIdCannotEscapeHistoryDirectory() throws Exception {
        String escapedName = "escaped-" + UUID.randomUUID();
        String sessionId = "../" + escapedName;
        Path historyDirectory = tempDirectory.resolve("traversal-history");
        Path escapedMessage = tempDirectory.resolve(escapedName + ".json");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        writeSessionIndex(objectMapper, historyDirectory, sessionId);
        AiChatHistoryServiceImpl service = new AiChatHistoryServiceImpl(objectMapper, historyDirectory);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.addMessage(addRequest(sessionId, "blocked")));

        assertEquals("ai.chat.history.sessionNotOwned", exception.getCode());
        assertTrue(Files.notExists(escapedMessage));
    }

    @Test
    void unownedInvalidSessionIdKeepsReadAndDeleteAsNoOps() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AiChatHistoryServiceImpl service = new AiChatHistoryServiceImpl(objectMapper, tempDirectory);

        assertAll(
                () -> assertTrue(service.getMessages("not-a-uuid", OWNER_ID).isEmpty()),
                () -> assertDoesNotThrow(() -> service.deleteSession("../not-owned", OWNER_ID))
        );
    }

    @Test
    void ownedInvalidSessionIdIsRejectedBeforeSessionIndexChanges() throws Exception {
        String sessionId = "../owned-invalid";
        Path historyDirectory = tempDirectory.resolve("owned-invalid-history");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        writeSessionIndex(objectMapper, historyDirectory, sessionId);
        Path sessionsPath = historyDirectory.resolve("sessions-" + OWNER_ID + ".json");
        byte[] original = Files.readAllBytes(sessionsPath);
        AiChatHistoryServiceImpl service = new AiChatHistoryServiceImpl(objectMapper, historyDirectory);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deleteSession(sessionId, OWNER_ID));

        assertEquals("ai.chat.history.sessionNotOwned", exception.getCode());
        assertArrayEquals(original, Files.readAllBytes(sessionsPath));
        assertEquals(List.of(sessionId), service.listSessions(OWNER_ID).stream().map(AiChatSession::getId).toList());
    }

    @Test
    void deleteOwnershipCheckHoldsTheSessionMutationLock() throws Exception {
        BlockingSessionReadObjectMapper objectMapper = new BlockingSessionReadObjectMapper();
        AiChatHistoryServiceImpl service = new AiChatHistoryServiceImpl(objectMapper, tempDirectory);
        AiChatSession original = service.createSession(OWNER_ID, "original");
        objectMapper.blockNextSessionRead();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> deletion = executor.submit(() -> service.deleteSession(original.getId(), OWNER_ID));
            assertTrue(objectMapper.awaitBlockedSessionRead());
            Future<AiChatSession> creation = executor.submit(
                    () -> service.createSession(OWNER_ID, "created while deleting"));

            assertThrows(TimeoutException.class, () -> creation.get(200, TimeUnit.MILLISECONDS));

            objectMapper.releaseSessionRead();
            deletion.get(5, TimeUnit.SECONDS);
            AiChatSession created = creation.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(created.getId()), service.listSessions(OWNER_ID).stream()
                    .map(AiChatSession::getId).toList());
        } finally {
            objectMapper.releaseSessionRead();
            executor.shutdownNow();
        }
    }

    private AiChatMessageAddRequest addRequest(String sessionId, String content) {
        AiChatMessageAddRequest request = new AiChatMessageAddRequest();
        request.setSessionId(sessionId);
        request.setUserId(OWNER_ID);
        request.setRole("user");
        request.setContent(content);
        return request;
    }

    private void writeSessionIndex(ObjectMapper objectMapper, Path historyDirectory, String sessionId)
            throws IOException {
        Files.createDirectories(historyDirectory);
        AiChatSession session = new AiChatSession();
        session.setId(sessionId);
        session.setUserId(OWNER_ID);
        session.setTitle("malicious session fixture");
        AiChatHistoryServiceImpl.SessionsFile sessionsFile = new AiChatHistoryServiceImpl.SessionsFile();
        sessionsFile.setSessions(List.of(session));
        objectMapper.writeValue(historyDirectory.resolve("sessions-" + OWNER_ID + ".json").toFile(), sessionsFile);
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(tempDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static <T> ObjectMapper objectMapper(Class<T> type, ToggleSerializer<T> serializer) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SimpleModule module = new SimpleModule();
        module.addSerializer(type, serializer);
        objectMapper.registerModule(module);
        return objectMapper;
    }

    private static final class ToggleSerializer<T> extends JsonSerializer<T> {
        private final String fieldName;
        private final Function<T, Object> valueExtractor;
        private final AtomicBoolean failWrites = new AtomicBoolean();
        private final AtomicBoolean failedAfterFlush = new AtomicBoolean();

        private ToggleSerializer(String fieldName, Function<T, Object> valueExtractor) {
            this.fieldName = fieldName;
            this.valueExtractor = valueExtractor;
        }

        @Override
        public void serialize(T value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeStartObject();
            generator.writeFieldName(fieldName);
            if (failWrites.get()) {
                generator.writeStartArray();
                generator.writeRaw("\"partial");
                generator.flush();
                failedAfterFlush.set(true);
                throw new IOException("forced serialization failure");
            }
            serializers.defaultSerializeValue(valueExtractor.apply(value), generator);
            generator.writeEndObject();
        }

        private void failWrites() {
            failWrites.set(true);
        }

        private boolean failedAfterFlush() {
            return failedAfterFlush.get();
        }
    }

    private static final class BlockingSessionReadObjectMapper extends ObjectMapper {
        private final AtomicBoolean blockNextSessionRead = new AtomicBoolean();
        private final CountDownLatch sessionReadBlocked = new CountDownLatch(1);
        private final CountDownLatch continueSessionRead = new CountDownLatch(1);

        private BlockingSessionReadObjectMapper() {
            findAndRegisterModules();
        }

        @Override
        public <T> T readValue(File source, Class<T> valueType) throws IOException {
            if (valueType == AiChatHistoryServiceImpl.SessionsFile.class
                    && blockNextSessionRead.compareAndSet(true, false)) {
                sessionReadBlocked.countDown();
                try {
                    if (!continueSessionRead.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting to continue the session read");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting to continue the session read", e);
                }
            }
            return super.readValue(source, valueType);
        }

        private void blockNextSessionRead() {
            blockNextSessionRead.set(true);
        }

        private boolean awaitBlockedSessionRead() throws InterruptedException {
            return sessionReadBlocked.await(5, TimeUnit.SECONDS);
        }

        private void releaseSessionRead() {
            continueSessionRead.countDown();
        }
    }
}
