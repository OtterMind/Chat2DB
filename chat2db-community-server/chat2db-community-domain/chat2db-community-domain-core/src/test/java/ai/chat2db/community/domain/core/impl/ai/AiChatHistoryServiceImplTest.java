package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.tools.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatHistoryServiceImplTest {

    private static final long OWNER_ID = 42L;
    private static final long OTHER_USER_ID = 84L;

    @TempDir
    Path tempDirectory;

    @Test
    void springSelectsTheProductionConstructor() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AutowiredAnnotationBeanPostProcessor postProcessor = new AutowiredAnnotationBeanPostProcessor();
        postProcessor.setBeanFactory(beanFactory);
        beanFactory.addBeanPostProcessor(postProcessor);
        beanFactory.registerSingleton("objectMapper", new ObjectMapper());

        assertNotNull(beanFactory.createBean(AiChatHistoryServiceImpl.class));
    }

    @Test
    void deleteSessionDoesNotDeleteAnotherUsersMessages() {
        AiChatHistoryServiceImpl service = new AiChatHistoryServiceImpl(
                new ObjectMapper().findAndRegisterModules(), tempDirectory);
        AiChatSession session = service.createSession(OWNER_ID, "owner session");
        service.addMessage(addRequest(session.getId(), OWNER_ID, "keep this message"));
        Path messageFile = tempDirectory.resolve(session.getId() + ".json");

        service.deleteSession(session.getId(), OTHER_USER_ID);

        assertTrue(Files.exists(messageFile));
        assertEquals(1, service.listSessions(OWNER_ID).size());
        assertEquals(1, service.getMessages(session.getId(), OWNER_ID).size());

        service.deleteSession(session.getId(), OWNER_ID);
        assertFalse(Files.exists(messageFile));
    }

    @Test
    void addMessageRejectsAnotherUsersSession() throws Exception {
        AiChatHistoryServiceImpl service = new AiChatHistoryServiceImpl(
                new ObjectMapper().findAndRegisterModules(), tempDirectory);
        AiChatSession session = service.createSession(OWNER_ID, "owner session");
        service.addMessage(addRequest(session.getId(), OWNER_ID, "original message"));
        Path messageFile = tempDirectory.resolve(session.getId() + ".json");
        String originalMessages = Files.readString(messageFile);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.addMessage(addRequest(session.getId(), OTHER_USER_ID, "injected message")));

        assertEquals("ai.chat.history.sessionNotOwned", exception.getCode());
        assertEquals(originalMessages, Files.readString(messageFile));
        assertEquals(1, service.getMessages(session.getId(), OWNER_ID).size());
    }

    private AiChatMessageAddRequest addRequest(String sessionId, Long userId, String content) {
        AiChatMessageAddRequest request = new AiChatMessageAddRequest();
        request.setSessionId(sessionId);
        request.setUserId(userId);
        request.setRole("user");
        request.setContent(content);
        return request;
    }
}
