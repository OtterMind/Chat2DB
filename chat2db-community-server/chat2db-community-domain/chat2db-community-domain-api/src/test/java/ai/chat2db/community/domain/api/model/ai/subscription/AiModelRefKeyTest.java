package ai.chat2db.community.domain.api.model.ai.subscription;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelRefKeyTest {

    @Test
    void roundTripsSupportedSubscriptionReferenceAndRejectsMalformedInput() {
        AiModelRef ref = new AiModelRef(
                AiAccessType.SUBSCRIPTION,
                AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER,
                "gpt-5.4");

        String encoded = AiModelRefKey.encode(ref);

        assertEquals(ref, AiModelRefKey.decode(encoded).orElseThrow());
        assertTrue(AiModelRefKey.decode("SUBSCRIPTION::XAI::CHATGPT_CODEX_APP_SERVER::grok").isEmpty());
        assertTrue(AiModelRefKey.decode("bad").isEmpty());
    }
}
