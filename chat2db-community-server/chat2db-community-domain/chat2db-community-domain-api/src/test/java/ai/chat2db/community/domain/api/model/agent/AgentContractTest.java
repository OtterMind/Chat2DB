package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCapabilities;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCapability;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContractTest {

    @Test
    void agentDefinitionKeepsRuntimeSelectionExplicit() {
        AgentDefinition definition = definition();

        assertEquals(AgentRuntimeType.PI, definition.runtimeType());
        assertThrows(IllegalArgumentException.class, () -> new AgentDefinition(
                "default", "Default agent", null, null,
                AgentRuntimeType.PI, "model-config", 0));
    }

    @Test
    void agentSessionIsExplicitlyV2() {
        LocalDateTime now = LocalDateTime.now();
        AgentRuntimeBinding binding = new AgentRuntimeBinding(
                AgentRuntimeType.PI, "0.85.1", "jsonl-rpc", "external-session", null, 1);
        AgentSession session = new AgentSession(
                AgentSession.SCHEMA_VERSION, "session", 1L, definition(), binding, AgentSessionStatus.CREATED,
                "New session", 0, now, now);

        assertEquals(2, session.schemaVersion());
        assertEquals(AgentRuntimeType.PI, session.runtimeBinding().runtimeType());
        assertThrows(IllegalArgumentException.class, () -> new AgentSession(
                1, "session", 1L, definition(), binding, AgentSessionStatus.CREATED,
                "New session", 0, now, now));
    }

    @Test
    void runtimeTypesArePlatformControlled() {
        assertEquals(List.of(AgentRuntimeType.PI, AgentRuntimeType.CODEX, AgentRuntimeType.DSH),
                List.of(AgentRuntimeType.values()));
    }

    @Test
    void eventsDefensivelyCopyPayloads() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("delta", "hello");
        AgentEvent event = new AgentEvent(
                "event", "session", "run", 1, AgentEventType.ASSISTANT_TEXT_DELTA,
                payload, LocalDateTime.now());

        payload.put("delta", "changed");

        assertEquals("hello", event.payload().get("delta"));
        assertThrows(UnsupportedOperationException.class, () -> event.payload().put("new", "value"));
    }

    @Test
    void capabilitiesDefensivelyCopySupportedValues() {
        Set<AgentRuntimeCapability> supported = new java.util.HashSet<>();
        supported.add(AgentRuntimeCapability.STREAMING);
        AgentRuntimeCapabilities capabilities = new AgentRuntimeCapabilities(supported, 1);

        supported.add(AgentRuntimeCapability.NATIVE_SANDBOX);

        assertTrue(capabilities.supports(AgentRuntimeCapability.STREAMING));
        assertFalse(capabilities.supports(AgentRuntimeCapability.NATIVE_SANDBOX));
        assertThrows(UnsupportedOperationException.class,
                () -> capabilities.supported().add(AgentRuntimeCapability.CANCELLATION));
    }

    @Test
    void runtimeEnvironmentReportDoesNotExposeMutableCollections() {
        List<String> checks = new ArrayList<>(List.of("binary"));
        Map<String, String> diagnostics = new HashMap<>(Map.of("architecture", "arm64"));
        AgentRuntimeEnvironmentReport report = new AgentRuntimeEnvironmentReport(
                AgentRuntimeType.PI, AgentRuntimeEnvironmentStatus.READY, "0.85.1",
                "macos", "arm64", checks, diagnostics, LocalDateTime.now());

        checks.add("rpc");
        diagnostics.put("status", "changed");

        assertEquals(List.of("binary"), report.checks());
        assertEquals(Map.of("architecture", "arm64"), report.diagnostics());
        assertTrue(report.isUsable());
    }

    @Test
    void terminalStatesAreExplicit() {
        assertFalse(AgentRunStatus.SUSPENDED.isTerminal());
        assertTrue(AgentRunStatus.COMPLETED.isTerminal());
        assertTrue(AgentRunStatus.UNKNOWN.isTerminal());
        assertFalse(AgentApprovalStatus.PENDING.isTerminal());
        assertTrue(AgentApprovalStatus.EXPIRED.isTerminal());
    }

    @Test
    void approvalRequiresCanonicalSha256() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);
        String sha256 = "a".repeat(64);
        AgentApproval approval = new AgentApproval(
                "approval", "session", "run", "tool-call", AgentApprovalStatus.PENDING,
                AgentApprovalScope.ONCE, sha256, expiresAt);

        assertEquals(sha256, approval.subjectSha256());
        assertThrows(IllegalArgumentException.class, () -> new AgentApproval(
                "approval", "session", "run", "tool-call", AgentApprovalStatus.PENDING,
                AgentApprovalScope.ONCE, "not-a-sha", expiresAt));
    }

    private AgentDefinition definition() {
        return new AgentDefinition(
                "default", "Default agent", null, "You are a database assistant.",
                AgentRuntimeType.PI, "model-config", 1);
    }
}
