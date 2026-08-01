package ai.chat2db.community.domain.api.model.ai.subscription;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSubscriptionContractsTest {

    @Test
    void modelRefRejectsRouteAndAccessTypeMismatch() {
        assertThrows(IllegalArgumentException.class, () -> new AiModelRef(
                AiAccessType.API_KEY,
                AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER,
                "gpt-5"));

        assertThrows(IllegalArgumentException.class, () -> new AiModelRef(
                AiAccessType.SUBSCRIPTION,
                AiProviderEnum.CLAUDE,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER,
                "claude"));
    }

    @Test
    void attemptStateMachineAllowsOnlyDocumentedForwardTransitions() {
        assertTrue(AiAttemptState.CREATED.canTransitionTo(AiAttemptState.SUBMITTING));
        assertTrue(AiAttemptState.SUBMITTING.canTransitionTo(AiAttemptState.ACTIVE));
        assertTrue(AiAttemptState.SUBMITTING.canTransitionTo(AiAttemptState.OUTCOME_UNKNOWN));
        assertTrue(AiAttemptState.ACTIVE.canTransitionTo(AiAttemptState.TOOL_ACTIVE));
        assertTrue(AiAttemptState.ACTIVE.canTransitionTo(AiAttemptState.OUTCOME_UNKNOWN));
        assertTrue(AiAttemptState.TOOL_ACTIVE.canTransitionTo(AiAttemptState.TOOL_OUTCOME_UNKNOWN));
        assertTrue(AiAttemptState.OUTPUT_VISIBLE.canTransitionTo(AiAttemptState.COMPLETED));
        assertTrue(AiAttemptState.OUTPUT_VISIBLE.canTransitionTo(AiAttemptState.OUTCOME_UNKNOWN));

        assertFalse(AiAttemptState.COMPLETED.canTransitionTo(AiAttemptState.ACTIVE));
        assertFalse(AiAttemptState.ACTIVE.canTransitionTo(AiAttemptState.CREATED));
        assertFalse(AiAttemptState.OUTPUT_VISIBLE.canTransitionTo(AiAttemptState.TOOL_OUTCOME_UNKNOWN));
        assertFalse(AiAttemptState.OUTCOME_UNKNOWN.canTransitionTo(AiAttemptState.SUBMITTING));
    }

    @Test
    void providerConnectionStateDoesNotPretendDiscoveryFailureIsLogout() {
        assertTrue(AiProviderConnectionState.CONNECTED.canTransitionTo(
                AiProviderConnectionState.DISCOVERY_FAILED));
        assertTrue(AiProviderConnectionState.DISCOVERY_FAILED.canTransitionTo(
                AiProviderConnectionState.CONNECTED));
        assertTrue(AiProviderConnectionState.CONNECTED.canTransitionTo(
                AiProviderConnectionState.DISCONNECTING));

        assertFalse(AiProviderConnectionState.DISCOVERY_FAILED.canTransitionTo(
                AiProviderConnectionState.DISCONNECTED));
    }

    @Test
    void unsupportedRuntimeSurfacesRemainDisabled() {
        assertFalse(AiSubscriptionRuntimeGate.evaluate(
                true, true, false, true, true, true, true).enabled());
        assertFalse(AiSubscriptionRuntimeGate.evaluate(
                true, true, true, true, false, true, true).enabled());
        assertFalse(AiSubscriptionRuntimeGate.evaluate(
                true, true, true, true, true, false, true).enabled());
        assertFalse(AiSubscriptionRuntimeGate.evaluate(
                false, true, true, true, true, true, true).enabled());

        assertTrue(AiSubscriptionRuntimeGate.evaluate(
                true, true, true, true, true, true, true).enabled());
    }
}
