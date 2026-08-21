package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonEnvironmentResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesOnlyPathAndExplicitProfileReferencesWithoutCopyingWholeEnvironment() throws Exception {
        Path executable = temporaryDirectory.resolve("codex");
        Files.writeString(executable, "fake");
        executable.toFile().setExecutable(true);
        assertTrue(Files.isExecutable(executable));
        Map<String, String> daemonEnvironment = Map.of(
                "PATH", temporaryDirectory.toString(),
                "DEDICATED_CODEX_HOME", temporaryDirectory.resolve("codex-home").toString(),
                "UNRELATED_SECRET", "must-not-be-forwarded");
        DaemonEnvironmentResolver resolver = new DaemonEnvironmentResolver(daemonEnvironment::get);
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setExecutable("codex");
        profile.setEnvironmentReferences(Map.of("CODEX_HOME", "DEDICATED_CODEX_HOME"));

        Map<String, String> resolved = resolver.resolve(profile);

        assertEquals(Map.of(
                "PATH", temporaryDirectory.toString(),
                "CODEX_HOME", temporaryDirectory.resolve("codex-home").toString()), resolved);
        assertEquals(executable, resolver.resolveExecutable(profile, resolved));
        assertFalse(resolved.containsKey("UNRELATED_SECRET"));
    }

    @Test
    void failsClosedWhenReferencedEnvironmentOrExecutableIsMissing() {
        DaemonEnvironmentResolver resolver = new DaemonEnvironmentResolver(name -> null);
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setExecutable("codex");
        profile.setEnvironmentReferences(Map.of("CODEX_HOME", "MISSING_CODEX_HOME"));

        assertThrows(IllegalStateException.class, () -> resolver.resolve(profile));
        profile.setEnvironmentReferences(Map.of());
        assertThrows(IllegalStateException.class,
                () -> resolver.resolveExecutable(profile, Map.of()));
    }

    @Test
    void discoveredPathOverridesDesktopPathWithoutForwardingOtherValues() {
        DaemonEnvironmentResolver resolver = new DaemonEnvironmentResolver(
                name -> "PATH".equals(name) ? "desktop-path" : null,
                Map.of("PATH", "login-shell-path", "SECRET", "not-forwarded"));
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setExecutable(temporaryDirectory.resolve("missing").toString());
        profile.setEnvironmentReferences(Map.of());

        assertEquals(Map.of("PATH", "login-shell-path"), resolver.resolve(profile));
    }
}
