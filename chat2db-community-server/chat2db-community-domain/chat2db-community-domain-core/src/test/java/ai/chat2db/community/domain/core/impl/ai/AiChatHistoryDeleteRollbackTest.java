package ai.chat2db.community.domain.core.impl.ai;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatHistoryDeleteRollbackTest {

    private static final long OWNER_ID = 42L;
    private static final long OTHER_USER_ID = 84L;

    @TempDir
    Path tempDirectory;

    @Test
    void failedMessageDeletionRestoresSessionAndCanBeRetried() throws Exception {
        AiChatHistoryServiceImpl service = service(new ObjectMapper().findAndRegisterModules());
        AiChatSession session = sessionWithMessage(service);
        Path messagePath = messagePath(session);
        byte[] originalMessages = Files.readAllBytes(messagePath);
        Path sentinel = replaceMessageFileWithNonEmptyDirectory(messagePath);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.deleteSession(session.getId(), OWNER_ID));

        assertEquals("ai.chat.history.deleteMessagesFailed", failure.getCode());
        assertEquals(List.of(session.getId()), service.listSessions(OWNER_ID).stream()
                .map(AiChatSession::getId).toList());
        assertTrue(Files.exists(sentinel));

        Files.delete(sentinel);
        Files.delete(messagePath);
        Files.write(messagePath, originalMessages);

        assertDoesNotThrow(() -> service.deleteSession(session.getId(), OWNER_ID));
        assertTrue(service.listSessions(OWNER_ID).isEmpty());
        assertFalse(Files.exists(messagePath));
    }

    @Test
    void nonOwnerDoesNotTouchUndeletableMessagePath() throws Exception {
        AiChatHistoryServiceImpl service = service(new ObjectMapper().findAndRegisterModules());
        AiChatSession session = sessionWithMessage(service);
        Path sentinel = replaceMessageFileWithNonEmptyDirectory(messagePath(session));

        assertDoesNotThrow(() -> service.deleteSession(session.getId(), OTHER_USER_ID));

        assertEquals(List.of(session.getId()), service.listSessions(OWNER_ID).stream()
                .map(AiChatSession::getId).toList());
        assertTrue(Files.exists(sentinel));
    }

    @Test
    void rollbackFailureIsSuppressedOnMessageDeletionFailure() throws Exception {
        AtomicBoolean failRollback = new AtomicBoolean();
        AtomicInteger writesAfterFailureArmed = new AtomicInteger();
        ObjectMapper mapper = rollbackFailingMapper(failRollback, writesAfterFailureArmed);
        AiChatHistoryServiceImpl service = service(mapper);
        AiChatSession session = sessionWithMessage(service);
        replaceMessageFileWithNonEmptyDirectory(messagePath(session));
        failRollback.set(true);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.deleteSession(session.getId(), OWNER_ID));

        assertEquals("ai.chat.history.deleteMessagesFailed", failure.getCode());
        assertEquals(1, failure.getSuppressed().length);
        BusinessException rollbackFailure = assertInstanceOf(BusinessException.class,
                failure.getSuppressed()[0]);
        assertEquals("ai.chat.history.persistSessionsFailed", rollbackFailure.getCode());
    }

    private AiChatHistoryServiceImpl service(ObjectMapper mapper) {
        return new AiChatHistoryServiceImpl(mapper, tempDirectory);
    }

    private AiChatSession sessionWithMessage(AiChatHistoryServiceImpl service) {
        AiChatSession session = service.createSession(OWNER_ID, "rollback session");
        AiChatMessageAddRequest request = new AiChatMessageAddRequest();
        request.setSessionId(session.getId());
        request.setUserId(OWNER_ID);
        request.setRole("user");
        request.setContent("keep this message");
        service.addMessage(request);
        return session;
    }

    private Path messagePath(AiChatSession session) {
        return tempDirectory.resolve(session.getId() + ".json");
    }

    private Path replaceMessageFileWithNonEmptyDirectory(Path messagePath) throws IOException {
        Files.delete(messagePath);
        Files.createDirectory(messagePath);
        return Files.writeString(messagePath.resolve("sentinel"), "keep");
    }

    private ObjectMapper rollbackFailingMapper(AtomicBoolean failRollback, AtomicInteger writesAfterFailureArmed) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SimpleModule module = new SimpleModule();
        module.addSerializer(AiChatHistoryServiceImpl.SessionsFile.class,
                new JsonSerializer<AiChatHistoryServiceImpl.SessionsFile>() {
                    @Override
                    public void serialize(AiChatHistoryServiceImpl.SessionsFile value, JsonGenerator generator,
                                          SerializerProvider serializers) throws IOException {
                        if (failRollback.get() && writesAfterFailureArmed.incrementAndGet() == 2) {
                            throw new IOException("simulated sessions rollback failure");
                        }
                        generator.writeStartObject();
                        generator.writeFieldName("sessions");
                        serializers.defaultSerializeValue(value.getSessions(), generator);
                        generator.writeEndObject();
                    }
                });
        mapper.registerModule(module);
        return mapper;
    }
}
