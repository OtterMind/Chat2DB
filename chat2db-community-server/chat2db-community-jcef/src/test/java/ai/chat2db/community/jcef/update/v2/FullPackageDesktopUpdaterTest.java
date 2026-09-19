package ai.chat2db.community.jcef.update.v2;

import ai.chat2db.community.jcef.utils.SingleInstanceUtil;
import ai.chat2db.community.updater.v2.model.InstalledAppVersion;
import ai.chat2db.community.updater.v2.verification.ManifestCanonicalizer;
import ai.chat2db.community.updater.v2.model.ReleaseIndex;
import ai.chat2db.community.updater.v2.model.ReleaseReference;
import ai.chat2db.community.updater.v2.enums.ReleaseStatusEnum;
import ai.chat2db.community.updater.v2.runtime.RuntimePlatformDetector;
import ai.chat2db.community.updater.v2.enums.UpdateArchitectureEnum;
import ai.chat2db.community.updater.v2.enums.UpdateChannelEnum;
import ai.chat2db.community.updater.v2.discovery.UpdateDiscoveryService;
import ai.chat2db.community.updater.v2.installation.UpdateLayout;
import ai.chat2db.community.updater.v2.model.UpdateManifest;
import ai.chat2db.community.updater.v2.verification.UpdateManifestVerifier;
import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePhaseEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePlatformEnum;
import ai.chat2db.community.updater.v2.enums.UpdateScopeEnum;
import ai.chat2db.community.updater.v2.model.UpdateTransaction;
import ai.chat2db.community.updater.v2.audit.UpdateAuditLog;
import ai.chat2db.community.updater.v2.transport.UpdateTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Duration;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullPackageDesktopUpdaterTest {

    private static final String BASE = "https://cdn.example.com/download/updates-v2/";

    @TempDir
    Path temporaryDirectory;

    @Test
    void appCheckUpdateDoesNotPersistFailureStateInTheDiscoveryPath() throws Exception {
        UpdatePlatformEnum platform = RuntimePlatformDetector.platform();
        UpdateArchitectureEnum architecture = RuntimePlatformDetector.architecture();
        UpdatePackageTypeEnum packageType = directPackageType(platform);
        UpdateLayout layout = layout();
        Files.createDirectories(layout.appDirectory());
        new ObjectMapper().writeValue(layout.appDirectory().resolve("version.json").toFile(),
            new InstalledAppVersion("5.3.3", 100, "installed"));

        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UpdateManifest manifest = signedManifest(keyPair, platform, architecture, packageType);
        StubTransport transport = new StubTransport();
        String manifestUrl = BASE + "stable/5.3.4/manifest.json";
        transport.put(BASE + "stable/latest_version.json", new ReleaseIndex(
            2, 101, ReleaseStatusEnum.ACTIVE, UpdateChannelEnum.STABLE,
            List.of(new ReleaseReference("5.3.4", platform, architecture, packageType, manifestUrl))
        ));
        transport.put(manifestUrl, manifest);
        UpdateTransaction rolledBack = new UpdateTransaction(
            "tx-rollback", "5.3.3", "5.3.4", 101, manifest.packageSha256(),
            UpdatePhaseEnum.FAILED, 10, 20, "candidate failed"
        );
        UpdateAuditLog.open(layout, rolledBack.transactionId(), "TEST").state(rolledBack);

        FullPackageDesktopUpdater updater = new FullPackageDesktopUpdater(
            layout,
            "COMMUNITY",
            packageType,
            transport,
            new UpdateDiscoveryService(
                transport,
                new UpdateManifestVerifier(Map.of("release", keyPair.getPublic())),
                BASE
            )
        );

        assertTrue(updater.appCheckUpdate().needsUpdate());
        Path auditFile;
        try (var files = Files.list(layout.logsDirectory())) {
            auditFile = files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("update-"))
                .filter(path -> !path.equals(layout.auditLogFile(rolledBack.transactionId())))
                .findFirst().orElseThrow();
        }
        String audit = Files.readString(auditFile);
        assertTrue(audit.contains("stage=DISCOVERY"));
        assertTrue(audit.contains("event=START"));
        assertTrue(audit.contains("event=SELECTED"));
        assertFalse(audit.contains("event=SUPPRESSED"));
    }

    @Test
    void failedHandoffKeepsTheApplicationAliveWhenTheHelperNeverAcknowledges() throws Exception {
        UpdateLayout layout = layout();
        UpdatePlatformEnum platform = RuntimePlatformDetector.platform();
        UpdatePackageTypeEnum packageType = directPackageType(platform);
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UpdateManifest manifest = signedManifest(keyPair, platform,
            RuntimePlatformDetector.architecture(), packageType);
        StubTransport transport = new StubTransport();
        FullPackageDesktopUpdater updater = new FullPackageDesktopUpdater(layout, "COMMUNITY", packageType,
            transport, new UpdateDiscoveryService(transport,
                new UpdateManifestVerifier(Map.of("release", keyPair.getPublic())), BASE));
        UpdateTransaction prepared = new UpdateTransaction("tx-ack", "5.3.3", manifest.version(),
            manifest.releaseEpoch(), manifest.packageSha256(), UpdatePhaseEnum.PRECHECKED, 10, 20, null);
        Field transactionField = field("preparedTransaction");
        transactionField.set(updater, prepared);
        field("preparedManifest").set(updater, manifest);
        UpdateAuditLog audit = UpdateAuditLog.open(layout, prepared.transactionId(), "TEST");
        field("auditLog").set(updater, audit);
        field("helperAckTimeout").set(updater, Duration.ofMillis(400L));
        AtomicBoolean helperStartRequested = new AtomicBoolean();
        AtomicBoolean helperUnloaded = new AtomicBoolean();
        field("helperStarter").set(updater, (FullPackageDesktopUpdater.HelperStarter)
            (command, workDirectory, stdout, stderr, id) -> {
                helperStartRequested.set(true);
                return () -> helperUnloaded.set(true);
            });
        Path fakeRuntime = Files.createDirectories(temporaryDirectory.resolve("fake-runtime/bin"));
        Files.writeString(fakeRuntime.resolve("java"), "java");
        Path helperSource = layout.appDirectory().resolve("tools/chat2db-updater.jar");
        Files.createDirectories(helperSource.getParent());
        Files.writeString(helperSource, "helper");
        String javaHome = System.getProperty("java.home");
        boolean installed;
        try {
            System.setProperty("java.home", temporaryDirectory.resolve("fake-runtime").toString());
            installed = (boolean) method("installPreparedUpdate").invoke(updater);
        } finally {
            System.setProperty("java.home", javaHome);
        }

        assertTrue(helperStartRequested.get(), "the prepared handoff must ask the starter to run the helper");
        assertTrue(helperUnloaded.get(),
            "a helper that never acknowledged must be unloaded so it cannot switch anything later");
        assertFalse(installed, "a helper that never acknowledges must fail the handoff");
        assertFalse((boolean) field("helperStarted").get(updater),
            "a failed handoff must keep the application running instead of exiting into a dead end");
        UpdateTransaction failed = (UpdateTransaction) transactionField.get(updater);
        assertEquals(UpdatePhaseEnum.FAILED, failed.phase());
        assertTrue(failed.failureMessage().contains("did not acknowledge"), failed.failureMessage());
        String log = Files.readString(layout.auditLogFile(prepared.transactionId()));
        assertTrue(log.contains("stage=HANDOFF event=ACK_WAIT"), log);
        assertTrue(log.contains("helperAck=false"), log);
        assertTrue(log.contains("stage=HANDOFF event=FAILED"), log);
    }

    @Test
    void pendingLaunchBlocksInstallationAndFailedHandoffRestoresLaunchDelivery() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path output = temporaryDirectory.resolve("installation.log");
        Path argumentFile = temporaryDirectory.resolve("java.args");
        List<String> arguments = List.of(
            "-Djava.awt.headless=true", "-Duser.home=" + temporaryDirectory,
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            InstallationProcess.class.getName(), temporaryDirectory.toString()
        );
        Files.write(argumentFile, arguments.stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList());
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + argumentFile)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Installation regression process timed out");
            assertEquals(0, process.exitValue(), () -> readOutput(output));
            assertTrue(Files.exists(temporaryDirectory.resolve("verified")), () -> readOutput(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void clearsReadOnlyHelperCopyBeforeReplacementOnWindows() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        UpdatePlatformEnum platform = RuntimePlatformDetector.platform();
        UpdatePackageTypeEnum packageType = directPackageType(platform);
        UpdateLayout layout = layout();
        FullPackageDesktopUpdater updater = new FullPackageDesktopUpdater(
            layout,
            "COMMUNITY",
            packageType,
            new StubTransport(),
            new UpdateDiscoveryService(new StubTransport(),
                new UpdateManifestVerifier(Map.of()), BASE)
        );
        Field auditField = FullPackageDesktopUpdater.class.getDeclaredField("auditLog");
        auditField.setAccessible(true);
        auditField.set(updater, UpdateAuditLog.open(layout, "tx-read-only", "TEST"));

        Path source = layout.appDirectory().resolve("tools/chat2db-updater.jar");
        Path target = layout.workDirectory().resolve("chat2db-updater.jar");
        Files.createDirectories(source.getParent());
        Files.createDirectories(target.getParent());
        Files.writeString(source, "new-helper");
        Files.writeString(target, "old-helper");
        DosFileAttributeView targetAttributes = Files.getFileAttributeView(target, DosFileAttributeView.class);
        targetAttributes.setReadOnly(true);

        var copyHelper = FullPackageDesktopUpdater.class.getDeclaredMethod("copyHelper", Path.class, Path.class);
        copyHelper.setAccessible(true);
        copyHelper.invoke(updater, source, target);

        assertEquals("new-helper", Files.readString(target));
        assertFalse(targetAttributes.readAttributes().isReadOnly());
    }

    private static Field field(String name) throws Exception {
        Field field = FullPackageDesktopUpdater.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static java.lang.reflect.Method method(String name) throws Exception {
        java.lang.reflect.Method method = FullPackageDesktopUpdater.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static String readOutput(Path output) {
        try { return Files.readString(output); }
        catch (Exception exception) { return exception.toString(); }
    }

    public static final class InstallationProcess {
        public static void main(String[] args) {
            try {
                verify(Path.of(args[0]));
                System.exit(0);
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
        }

        private static void verify(Path temporary) throws Exception {
            FullPackageDesktopUpdaterTest fixture = new FullPackageDesktopUpdaterTest();
            fixture.temporaryDirectory = temporary;
            UpdateLayout layout = fixture.layout();
            UpdatePlatformEnum platform = RuntimePlatformDetector.platform();
            UpdatePackageTypeEnum packageType = directPackageType(platform);
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            UpdateManifest manifest = signedManifest(keyPair, platform,
                RuntimePlatformDetector.architecture(), packageType);
            StubTransport transport = new StubTransport();
            FullPackageDesktopUpdater updater = new FullPackageDesktopUpdater(layout, "COMMUNITY", packageType,
                transport, new UpdateDiscoveryService(transport,
                    new UpdateManifestVerifier(Map.of("release", keyPair.getPublic())), BASE));
            UpdateTransaction prepared = new UpdateTransaction("tx-install", "5.3.3", manifest.version(),
                manifest.releaseEpoch(), manifest.packageSha256(), UpdatePhaseEnum.PRECHECKED, 10, 20, null);
            Field transactionField = field("preparedTransaction");
            transactionField.set(updater, prepared);
            field("preparedManifest").set(updater, manifest);
            field("auditLog").set(updater, UpdateAuditLog.open(layout, prepared.transactionId(), "TEST"));

            Path state = temporary.resolve("instance");
            var register = SingleInstanceUtil.class.getDeclaredMethod("registerInstance", Path.class, String[].class);
            register.setAccessible(true);
            assertEquals(true, register.invoke(null, state, new String[0]));
            assertTrue(sendLaunch(state), "Forwarded launch must be acknowledged before installation");
            assertFalse(updater.triggerInstallation());
            assertEquals(prepared, transactionField.get(updater));
            assertFalse(Files.exists(layout.workDirectory()), "Pending launch must prevent helper preparation");

            AtomicInteger received = new AtomicInteger();
            SingleInstanceUtil.onReady(argument -> received.incrementAndGet());
            await(() -> received.get() == 2);
            AtomicBoolean drained = new AtomicBoolean();
            await(() -> {
                SingleInstanceUtil.guardExit(() -> { drained.set(true); return false; }).getAsBoolean();
                return drained.get();
            });

            String javaHome = System.getProperty("java.home");
            try {
                System.setProperty("java.home", temporary.resolve("missing-runtime").toString());
                assertFalse(updater.triggerInstallation());
            } finally {
                System.setProperty("java.home", javaHome);
            }
            UpdateTransaction failed = (UpdateTransaction) transactionField.get(updater);
            assertEquals(UpdatePhaseEnum.FAILED, failed.phase());
            assertTrue(failed.failureMessage().contains("Current Java runtime is missing"));
            assertFalse((boolean) field("helperStarted").get(updater));

            assertTrue(sendLaunch(state), "Failed installation must restore launch receipt");
            await(() -> received.get() == 3);
            Files.createFile(temporary.resolve("verified"));
        }

        private static boolean sendLaunch(Path state) throws Exception {
            var endpoint = new ObjectMapper().readTree(state.resolve("app.ipc.endpoint").toFile());
            try (Socket socket = new Socket("127.0.0.1", endpoint.get("port").asInt())) {
                socket.setSoTimeout(5000);
                DataOutputStream request = new DataOutputStream(socket.getOutputStream());
                request.writeUTF(endpoint.get("token").asText());
                request.writeInt(0);
                request.flush();
                return new DataInputStream(socket.getInputStream()).readBoolean();
            }
        }

        private static Field field(String name) throws Exception {
            Field field = FullPackageDesktopUpdater.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!condition.getAsBoolean()) {
                assertTrue(System.nanoTime() < deadline, "Timed out waiting for launch delivery");
                Thread.sleep(10);
            }
        }
    }

    private UpdateLayout layout() {
        Path install = temporaryDirectory.resolve("Applications/Chat2DB Community.app");
        return new UpdateLayout(
            install,
            install.resolve("Contents/app"),
            temporaryDirectory.resolve("cache"),
            temporaryDirectory.resolve("support")
        );
    }

    private static UpdatePackageTypeEnum directPackageType(UpdatePlatformEnum platform) {
        return switch (platform) {
            case MACOS -> UpdatePackageTypeEnum.MACOS_APP_ARCHIVE;
            case WINDOWS -> UpdatePackageTypeEnum.WINDOWS_EXE;
            case LINUX -> UpdatePackageTypeEnum.LINUX_APPIMAGE;
        };
    }

    private static UpdateManifest signedManifest(KeyPair keyPair, UpdatePlatformEnum platform,
            UpdateArchitectureEnum architecture, UpdatePackageTypeEnum packageType) throws Exception {
        UpdateManifest unsigned = new UpdateManifest(
            2, 101, ReleaseStatusEnum.ACTIVE, "COMMUNITY", UpdateChannelEnum.STABLE, "5.3.4", "5.3.401", "sha",
            platform, architecture, UpdateScopeEnum.FULL_PACKAGE, packageType,
            "https://cdn.example.com/package." + packageType.fileExtension(), 100, "a".repeat(64),
            packageType.singleFile() ? "." : "bin/chat2db",
            3, 3, "https://example.com/notes", "release", null
        );
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(ManifestCanonicalizer.canonicalBytes(unsigned));
        return new UpdateManifest(
            unsigned.schemaVersion(), unsigned.releaseEpoch(), unsigned.status(), unsigned.product(),
            unsigned.channel(), unsigned.version(), unsigned.nativeVersion(), unsigned.buildSha(), unsigned.platform(), unsigned.arch(),
            unsigned.updateScope(), unsigned.packageType(), unsigned.packageUrl(), unsigned.packageSize(),
            unsigned.packageSha256(), unsigned.launcherRelativePath(), unsigned.updaterProtocolVersion(),
            unsigned.minUpdaterProtocolVersion(),
            unsigned.releaseNotesUrl(), unsigned.keyId(), Base64.getEncoder().encodeToString(signer.sign())
        );
    }

    private static final class StubTransport implements UpdateTransport {
        private final Map<String, Object> values = new HashMap<>();

        void put(String url, Object value) {
            values.put(url, value);
        }

        @Override
        public <T> T getJson(String url, Class<T> type) {
            return type.cast(values.get(url));
        }

        @Override
        public Path download(String url, Path destination, long expectedSize, String expectedSha256,
                DownloadProgress listener) {
            throw new UnsupportedOperationException();
        }
    }
}
