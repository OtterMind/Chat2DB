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
    void discoversEveryExternalProviderAndUsesProviderSpecificVersionCommands() throws IOException {
        Path claude = executable("claude", "#!/bin/sh\necho '2.1.220 (Claude Code)'\n");
        Path codex = executable("codex", "#!/bin/sh\necho 'codex-cli 0.147.0'\n");
        Path opencode = executable("opencode", "#!/bin/sh\necho '1.17.7'\n");
        Path pi = executable("pi", "#!/bin/sh\necho '0.67.2'\n");
        Path hermes = executable("hermes", "#!/bin/sh\n"
                + "if [ \"$1\" = 'acp' ] && [ \"$2\" = '--version' ]; then echo 'Hermes ACP 0.2.0'; exit 0; fi\n"
                + "exit 1\n");
        Path dsh = executable("dsh", "#!/bin/sh\n"
                + "if [ \"$1\" = '--version' ]; then echo '0.1.0-rc.6'; exit 0; fi\n"
                + "exit 1\n");
        LocalRuntimeDiscovery discovery = new LocalRuntimeDiscovery(
                Map.of("PATH", temporaryDirectory.toString()), temporaryDirectory);

        List<LocalRuntimeInstallation> installations = discovery.discover(Set.of(
                AgentRuntimeProviderEnum.CLAUDE_CODE, AgentRuntimeProviderEnum.CODEX,
                AgentRuntimeProviderEnum.OPENCODE, AgentRuntimeProviderEnum.PI,
                AgentRuntimeProviderEnum.HERMES,
                AgentRuntimeProviderEnum.DSH));

        assertEquals(List.of(AgentRuntimeProviderEnum.CLAUDE_CODE, AgentRuntimeProviderEnum.CODEX,
                        AgentRuntimeProviderEnum.OPENCODE, AgentRuntimeProviderEnum.PI,
                        AgentRuntimeProviderEnum.HERMES, AgentRuntimeProviderEnum.DSH),
                installations.stream().map(LocalRuntimeInstallation::provider).toList());
        assertEquals(claude.toRealPath(), installations.get(0).executable());
        assertEquals("2.1.220 (Claude Code)", installations.get(0).version());
        assertEquals(codex.toRealPath(), installations.get(1).executable());
        assertEquals("codex-cli 0.147.0", installations.get(1).version());
        assertEquals(opencode.toRealPath(), installations.get(2).executable());
        assertEquals("1.17.7", installations.get(2).version());
        assertEquals(pi.toRealPath(), installations.get(3).executable());
        assertEquals("0.67.2", installations.get(3).version());
        assertEquals(hermes.toRealPath(), installations.get(4).executable());
        assertEquals("Hermes ACP 0.2.0", installations.get(4).version());
        assertEquals(dsh.toRealPath(), installations.get(5).executable());
        assertEquals("0.1.0-rc.6", installations.get(5).version());
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
