package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentFeature;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashAgentFeatureServiceTest {

    @Test
    void enablesOnlyWhenShellAndSandboxAreAvailable() {
        MemoryFlags flags = new MemoryFlags();
        BashAgentFeatureService service = new BashAgentFeatureService(
                flags,
                new BashEnvironmentChecker(
                        () -> "Linux",
                        Set.of(Path.of("/bin/bash"), Path.of("/usr/bin/bwrap"))::contains,
                        () -> false));

        assertFalse(service.check().enabled());
        assertTrue(service.enable().enabled());
        assertTrue(flags.isEnabled(AgentFeature.BASH));
        assertFalse(service.disable().enabled());
    }

    @Test
    void windowsGitBashWithoutSandboxRemainsBlocked() {
        BashAgentFeatureService service = new BashAgentFeatureService(
                new MemoryFlags(),
                new BashEnvironmentChecker(
                        () -> "Windows 11",
                        path -> path.toString().endsWith("bash.exe"),
                        () -> false));

        assertFalse(service.enable().available());
        assertFalse(service.check().enabled());
    }

    private static final class MemoryFlags implements AgentFeatureFlagStorage {
        private final Map<AgentFeature, Boolean> values = new EnumMap<>(AgentFeature.class);
        @Override public boolean isEnabled(AgentRuntimeType runtimeType) { return false; }
        @Override public void setEnabled(AgentRuntimeType runtimeType, boolean enabled) { }
        @Override public boolean isEnabled(AgentFeature feature) {
            return values.getOrDefault(feature, false);
        }
        @Override public void setEnabled(AgentFeature feature, boolean enabled) {
            values.put(feature, enabled);
        }
    }
}
