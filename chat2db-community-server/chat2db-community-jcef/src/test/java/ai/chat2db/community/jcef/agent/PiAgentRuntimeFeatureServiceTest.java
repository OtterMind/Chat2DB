package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiAgentRuntimeFeatureServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void enablesOnlyAfterInstallationAndVerification() {
        MemoryFlags flags = new MemoryFlags();
        AtomicInteger installs = new AtomicInteger();
        PiAgentRuntimeFeatureService service = new PiAgentRuntimeFeatureService(
                flags, checker(AgentRuntimeEnvironmentStatus.READY), environment -> {
                    installs.incrementAndGet();
                    return temporaryDirectory;
                });

        assertFalse(service.check(environment()).enabled());
        assertTrue(service.enable(environment()).enabled());
        assertEquals(1, installs.get());
        assertTrue(flags.isEnabled(AgentRuntimeType.PI));
        assertFalse(service.disable(environment()).enabled());
    }

    @Test
    void keepsFeatureDisabledWhenInstallationFails() {
        MemoryFlags flags = new MemoryFlags();
        PiAgentRuntimeFeatureService service = new PiAgentRuntimeFeatureService(
                flags, checker(AgentRuntimeEnvironmentStatus.BLOCKED), environment -> {
                    throw new IOException("download failed");
                });

        var state = service.enable(environment());

        assertFalse(state.enabled());
        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED, state.environment().status());
        assertEquals(List.of("INSTALL_FAILED"), state.environment().checks());
        assertFalse(flags.isEnabled(AgentRuntimeType.PI));
    }

    private PiRuntimeEnvironmentChecker checker(AgentRuntimeEnvironmentStatus status) {
        return new PiRuntimeEnvironmentChecker(new PiRuntimeLayout(temporaryDirectory, "0.85.1")) {
            @Override
            public AgentRuntimeEnvironmentReport inspect(AgentRuntimeEnvironmentRequest request) {
                return new AgentRuntimeEnvironmentReport(
                        AgentRuntimeType.PI, status, "0.85.1", "macos", "arm64",
                        List.of(), Map.of(), LocalDateTime.of(2026, 9, 9, 0, 0));
            }
        };
    }

    private AgentRuntimeEnvironmentRequest environment() {
        return new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64");
    }

    private static final class MemoryFlags implements AgentFeatureFlagStorage {
        private final Map<AgentRuntimeType, Boolean> values = new HashMap<>();
        @Override public boolean isEnabled(AgentRuntimeType runtimeType) {
            return values.getOrDefault(runtimeType, false);
        }
        @Override public void setEnabled(AgentRuntimeType runtimeType, boolean enabled) {
            values.put(runtimeType, enabled);
        }
        @Override public boolean isEnabled(ai.chat2db.community.domain.api.model.agent.AgentFeature feature) {
            return false;
        }
        @Override public void setEnabled(
                ai.chat2db.community.domain.api.model.agent.AgentFeature feature, boolean enabled) {
        }
    }
}
