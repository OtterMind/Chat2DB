package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRuntimeDiscoveryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversCodexAndHermesFromPathAndUsesProviderSpecificVersionCommands() throws IOException {
        Path codex = executable("codex", "#!/bin/sh\necho 'codex-cli 0.147.0'\n");
        Path hermes = executable("hermes", "#!/bin/sh\n"
                + "if [ \"$1\" = 'acp' ] && [ \"$2\" = '--version' ]; then echo 'Hermes ACP 0.2.0'; exit 0; fi\n"
                + "exit 1\n");
        LocalRuntimeDiscovery discovery = new LocalRuntimeDiscovery(
                Map.of("PATH", temporaryDirectory.toString()), temporaryDirectory);

        List<LocalRuntimeInstallation> installations = discovery.discover(Set.of(
                AgentRuntimeProviderEnum.CODEX, AgentRuntimeProviderEnum.HERMES));

        assertEquals(List.of(AgentRuntimeProviderEnum.CODEX, AgentRuntimeProviderEnum.HERMES),
                installations.stream().map(LocalRuntimeInstallation::provider).toList());
        assertEquals(codex.toRealPath(), installations.get(0).executable());
        assertEquals("codex-cli 0.147.0", installations.get(0).version());
        assertEquals(hermes.toRealPath(), installations.get(1).executable());
        assertEquals("Hermes ACP 0.2.0", installations.get(1).version());
    }

    @Test
    void explicitExecutableOverrideTakesPrecedenceOverPath() throws IOException {
        executable("codex", "#!/bin/sh\necho 'path version'\n");
        Path overrideDirectory = Files.createDirectory(temporaryDirectory.resolve("override"));
        Path override = executable(overrideDirectory, "codex-custom", "#!/bin/sh\necho 'override version'\n");
        LocalRuntimeDiscovery discovery = new LocalRuntimeDiscovery(Map.of(
                "PATH", temporaryDirectory.toString(),
                LocalRuntimeDiscovery.CODEX_PATH_ENV, override.toString()), temporaryDirectory);

        LocalRuntimeInstallation installation = discovery.discover(Set.of(AgentRuntimeProviderEnum.CODEX)).get(0);

        assertEquals(override.toRealPath(), installation.executable());
        assertEquals("override version", installation.version());
    }

    private Path executable(String name, String content) throws IOException {
        return executable(temporaryDirectory, name, content);
    }

    private Path executable(Path directory, String name, String content) throws IOException {
        Path file = Files.writeString(directory.resolve(name), content);
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }
}
