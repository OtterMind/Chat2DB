package ai.chat2db.community.start.ai.subscription.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionRuntimeManifestTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsPinnedBinaryMetadataWithoutSecretMaterial() throws Exception {
        Path binary = temporaryDirectory.resolve("codex-app-server");
        Files.writeString(binary, "fixture");
        Path manifest = temporaryDirectory.resolve("runtime.properties");
        Files.writeString(manifest, String.join("\n",
                "schemaVersion=1",
                "provider=OPENAI",
                "accessType=SUBSCRIPTION",
                "version=0.144.6",
                "protocolLabel=codex-app-server-jsonl-v2-0.144.6",
                "binary=codex-app-server",
                "binarySha256=" + "a".repeat(64)));

        SubscriptionRuntimeManifest loaded = SubscriptionRuntimeManifest.load(manifest.toString());

        assertEquals(binary, loaded.binaryPath());
        assertEquals("0.144.6", loaded.version());
        assertEquals("a".repeat(64), loaded.binarySha256());
        java.util.List<String> launch = loaded.stdioLaunchCommand();
        assertEquals(binary.toString(), launch.get(0));
        assertEquals("--listen", launch.get(launch.size() - 2));
        assertEquals("stdio://", launch.get(launch.size() - 1));
        // Force CodeMode off + prefer non-deferred MCP + loopback network for tools/call.
        assertEquals(true, launch.contains("features.code_mode=false"));
        assertEquals(true, launch.contains("features.code_mode_host=false"));
        assertEquals(true, launch.contains("features.tool_search_always_defer_mcp_tools=false"));
        assertEquals(true, launch.contains("sandbox_workspace_write.network_access=true"));
        assertEquals(false, launch.stream().anyMatch(a -> a.startsWith("code_mode.direct_only_tool_namespaces=")));
    }

    @Test
    void rejectsMissingBinaryAndPathTraversal() throws Exception {
        Path manifest = temporaryDirectory.resolve("runtime.properties");
        Files.writeString(manifest, String.join("\n",
                "schemaVersion=1",
                "provider=OPENAI",
                "accessType=SUBSCRIPTION",
                "version=0.144.6",
                "protocolLabel=pinned",
                "binary=../outside",
                "binarySha256=" + "b".repeat(64)));

        assertThrows(IllegalStateException.class, () -> SubscriptionRuntimeManifest.load(manifest.toString()));
    }

    @Test
    void resolvesRelativeManifestFromPackagedMacAppWhenWorkingDirectoryIsRoot() throws Exception {
        Path appRoot = temporaryDirectory.resolve("Chat2DB Subscription Preview.app");
        Path launcher = appRoot.resolve("Contents/MacOS/Chat2DB Community");
        Path binary = appRoot.resolve("Contents/app/codex-app-server/codex-app-server");
        Path manifest = binary.resolveSibling("runtime.properties");
        Files.createDirectories(launcher.getParent());
        Files.createDirectories(binary.getParent());
        Files.writeString(launcher, "fixture-launcher");
        Files.writeString(binary, "fixture-binary");
        Files.writeString(manifest, String.join("\n",
                "schemaVersion=1",
                "provider=OPENAI",
                "accessType=SUBSCRIPTION",
                "version=0.144.6",
                "protocolLabel=codex-app-server-jsonl-v2-0.144.6",
                "binary=codex-app-server",
                "binarySha256=" + "c".repeat(64)));

        String previousUserDirectory = System.getProperty("user.dir");
        String previousAppPath = System.getProperty("jpackage.app-path");
        try {
            System.setProperty("user.dir", "/");
            System.setProperty("jpackage.app-path", launcher.toString());

            SubscriptionRuntimeManifest loaded = SubscriptionRuntimeManifest.load(
                    "codex-app-server/runtime.properties");

            assertEquals(manifest, loaded.manifestPath());
            assertEquals(binary, loaded.binaryPath());
        } finally {
            restoreSystemProperty("user.dir", previousUserDirectory);
            restoreSystemProperty("jpackage.app-path", previousAppPath);
        }
    }

    @Test
    void packagedMacCandidateCannotEscapeContentsApp() throws Exception {
        Path appRoot = temporaryDirectory.resolve("Chat2DB Subscription Preview.app");
        Path launcher = appRoot.resolve("Contents/MacOS/Chat2DB Community");
        Path escapedManifest = appRoot.resolve("Contents/outside/runtime.properties");
        Files.createDirectories(launcher.getParent());
        Files.createDirectories(escapedManifest.getParent());
        Files.writeString(launcher, "fixture-launcher");
        Files.writeString(escapedManifest, "schemaVersion=1");

        assertThrows(IllegalStateException.class, () -> SubscriptionRuntimeManifest.resolvePackagedMacManifest(
                Path.of("../outside/runtime.properties"), launcher.toString()));
    }

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
