package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAccessType;
import ai.chat2db.community.domain.api.model.ai.subscription.AiRouteKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRefKey;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class AiRouteResolverTest {

    private final AiRouteResolver resolver = new AiRouteResolver();

    @Test
    void nullAccessTypeDefaultsToApiKeyRoute() {
        ChatRequest request = new ChatRequest();
        request.setInput("hello");
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-test");

        AiRouteDecision decision = resolver.resolve(request);

        assertTrue(decision.isApiKey());
        assertEquals(AiRouteKind.SPRING_AI_API_KEY, decision.routeKind());
        assertEquals(AiAccessType.API_KEY, decision.modelRef().accessType());
        assertNull(request.getAccessType());
    }

    @Test
    void explicitApiKeyAccessTypeUsesSpringAiRoute() {
        ChatRequest request = new ChatRequest();
        request.setInput("hello");
        request.setAccessType(AiAccessType.API_KEY);
        request.setProvider(AiProviderEnum.XAI);
        request.setModel("grok");

        AiRouteDecision decision = resolver.resolve(request);

        assertTrue(decision.isApiKey());
        assertEquals(AiProviderEnum.XAI, decision.modelRef().provider());
    }

    @Test
    void subscriptionAccessTypeRequiresOpenAiChatGptRoute() {
        ChatRequest request = new ChatRequest();
        request.setInput("hello");
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-5.4");
        request.setMessageId("msg-1");

        AiRouteDecision decision = resolver.resolve(request);

        assertTrue(decision.isSubscription());
        assertEquals(AiRouteKind.CHATGPT_CODEX_APP_SERVER, decision.routeKind());
        assertEquals("gpt-5.4", decision.modelRef().modelId());
    }

    @Test
    void subscriptionWithoutModelIsRejected() {
        ChatRequest request = new ChatRequest();
        request.setInput("hello");
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);

        AiRouteDecision decision = resolver.resolve(request);

        assertTrue(decision.isRejected());
        assertEquals("SUBSCRIPTION_MODEL_REQUIRED", decision.rejectCode());
    }

    @Test
    void subscriptionWithNonOpenAiProviderIsRejected() {
        ChatRequest request = new ChatRequest();
        request.setInput("hello");
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.CLAUDE);
        request.setModel("claude");

        AiRouteDecision decision = resolver.resolve(request);

        assertTrue(decision.isRejected());
        assertEquals("UNSUPPORTED_SUBSCRIPTION_MODEL_REF", decision.rejectCode());
    }

    @Test
    void backendModelRefKeyMustMatchExplicitProviderAndModel() {
        ChatRequest request = new ChatRequest();
        request.setInput("hello");
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-other");
        request.setModelRefKey(AiModelRefKey.encode(new AiModelRef(
                AiAccessType.SUBSCRIPTION,
                AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER,
                "gpt-5.4")));

        AiRouteDecision decision = resolver.resolve(request);

        assertTrue(decision.isRejected());
        assertEquals("SUBSCRIPTION_MODEL_REF_MISMATCH", decision.rejectCode());
    }

    @Test
    void accessTypeAndMessageIdSerializeAndDeserialize() {
        ChatRequest request = new ChatRequest();
        request.setInput("hello");
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setMessageId("msg-stable");
        request.setModel("gpt-5.4");
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModelRefKey("SUBSCRIPTION::OPENAI::CHATGPT_CODEX_APP_SERVER::gpt-5.4");

        String json = JSON.toJSONString(request);
        ChatRequest restored = JSON.parseObject(json, ChatRequest.class);

        assertEquals(AiAccessType.SUBSCRIPTION, restored.getAccessType());
        assertEquals("msg-stable", restored.getMessageId());
        assertTrue(json.contains("accessType"));
        assertTrue(json.contains("messageId"));
        assertTrue(json.contains("modelRefKey"));
        assertFalse(json.contains("apiKey") && json.contains("sk-"));
    }
}
