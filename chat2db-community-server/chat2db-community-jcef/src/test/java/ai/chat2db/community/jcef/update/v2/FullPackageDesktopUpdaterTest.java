package ai.chat2db.community.jcef.update.v2;

import ai.chat2db.community.jcef.update.DesktopUpdateCheckResult;
import ai.chat2db.community.jcef.utils.SingleInstanceUtil;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.updater.v2.model.InstalledAppVersion;
import ai.chat2db.community.updater.v2.verification.ManifestCanonicalizer;
import ai.chat2db.community.updater.v2.model.ReleaseIndex;
import ai.chat2db.community.updater.v2.model.ReleaseReference;
import ai.chat2db.community.updater.v2.enums.ReleaseStatusEnum;
import ai.chat2db.community.updater.v2.runtime.RuntimePlatformDetector;
import ai.chat2db.community.updater.v2.state.PreparedUpdateStore;
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
import ai.chat2db.community.updater.v2.model.UpdatePreferences;
import ai.chat2db.community.updater.v2.model.UpdateTransaction;
import ai.chat2db.community.updater.v2.audit.UpdateAuditLog;
import ai.chat2db.community.updater.v2.transport.HttpsUpdateTransport;
import ai.chat2db.community.updater.v2.transport.UpdateTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void resumingAFailedTransactionKeepsItsIdentityAndClearsTheFailure() throws Exception {
        FullPackageDesktopUpdater updater = new FullPackageDesktopUpdater(layout(),
            "COMMUNITY", directPackageType(RuntimePlatformDetector.platform()),
            new StubTransport(), new UpdateDiscoveryService(new StubTransport(),
                new UpdateManifestVerifier(Map.of()), BASE));
        UpdateAuditLog audit = UpdateAuditLog.open(layout(), "tx-retry-unit", "TEST");
        field("auditLog").set(updater, audit);
        UpdateTransaction failed = new UpdateTransaction("tx-retry-unit", "5.3.3", "5.3.4", 101L,
            "a".repeat(64), UpdatePhaseEnum.FAILED, 10L, 20L, "helper did not acknowledge");

        UpdateTransaction resumed = (UpdateTransaction) method("prepareForHandoff", UpdateTransaction.class)
            .invoke(updater, failed);

        assertEquals("tx-retry-unit", resumed.transactionId(), "the retry keeps one audit trail");
        assertEquals(UpdatePhaseEnum.QUIESCING, resumed.phase());
        assertNull(resumed.failureMessage());
        assertEquals(101L, resumed.releaseEpoch());
    }

    @Test
    void anEarlierAttemptsAcknowledgementDoesNotSatisfyTheNextHandoff() throws Exception {
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
        UpdateTransaction prepared = new UpdateTransaction("tx-stale-ack", "5.3.3", manifest.version(),
            manifest.releaseEpoch(), manifest.packageSha256(), UpdatePhaseEnum.QUIESCING, 10, 20, null);
        field("preparedTransaction").set(updater, prepared);
        field("preparedManifest").set(updater, manifest);
        field("auditLog").set(updater, UpdateAuditLog.open(layout, prepared.transactionId(), "TEST"));
        field("helperAckTimeout").set(updater, Duration.ofMillis(300L));
        Files.createDirectories(layout.logsDirectory());
        Files.writeString(layout.auditLogFile(prepared.transactionId()),
            "actor=HELPER stage=HANDOFF event=ACK outcome=PERSISTED\n", StandardOpenOption.CREATE);
        field("helperStarter").set(updater, (FullPackageDesktopUpdater.HelperStarter)
            (command, workDirectory, stdout, stderr) -> null);
        Path fakeRuntime = Files.createDirectories(temporaryDirectory.resolve("fake-runtime-ack/bin"));
        Files.writeString(fakeRuntime.resolve("java"), "java");
        Path helperSource = layout.appDirectory().resolve("tools/chat2db-updater.jar");
        Files.createDirectories(helperSource.getParent());
        Files.writeString(helperSource, "helper");
        String javaHome = System.getProperty("java.home");
        boolean installed;
        try {
            System.setProperty("java.home", temporaryDirectory.resolve("fake-runtime-ack").toString());
            installed = (boolean) method("installPreparedUpdate").invoke(updater);
        } finally {
            System.setProperty("java.home", javaHome);
        }

        assertFalse(installed, "a retry must wait for its own helper, not trust the previous ack");
    }

    @Test
    void fallsBackToADirectHelperProcessWhenTheAgentCannotBeLoaded() throws Exception {
        AtomicBoolean directStarted = new AtomicBoolean();
        AtomicReference<String> reported = new AtomicReference<>();

        Runnable cleanup = FullPackageDesktopUpdater.withDirectFallback(
            () -> {
                throw new IllegalStateException("Cannot load the update helper agent: exit=5");
            },
            () -> {
                directStarted.set(true);
                return null;
            },
            failure -> reported.set(failure.getMessage()));

        assertNull(cleanup);
        assertTrue(directStarted.get(), "a device where launchd refuses the agent must keep updating");
        assertEquals("Cannot load the update helper agent: exit=5", reported.get());
    }

    @Test
    void keepsTheAgentHelperWhenTheAgentLoads() throws Exception {
        AtomicBoolean directStarted = new AtomicBoolean();
        Runnable abort = () -> directStarted.set(true);

        Runnable cleanup = FullPackageDesktopUpdater.withDirectFallback(
            () -> abort,
            () -> {
                directStarted.set(true);
                return null;
            },
            failure -> { });

        assertSame(abort, cleanup);
        assertFalse(directStarted.get(), "the fallback must not run when the agent loaded");
    }

    @Test
    void reportsWhenBothLaunchPathsFail() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> FullPackageDesktopUpdater.withDirectFallback(
                () -> {
                    throw new IllegalStateException("agent failed");
                },
                () -> {
                    throw new IOException("direct failed");
                },
                reported -> { }));

        assertEquals("Cannot start the update helper", failure.getMessage());
        assertEquals("direct failed", failure.getCause().getMessage());
        assertEquals("agent failed", failure.getCause().getSuppressed()[0].getMessage());
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
            (command, workDirectory, stdout, stderr) -> {
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

        // A retry while the application is still running must reach the starter again instead of
        // being rejected as an invalid FAILED -> QUIESCING transition.
        field("helperStarter").set(updater, (FullPackageDesktopUpdater.HelperStarter)
            (command, workDirectory, stdout, stderr) -> {
                throw new IllegalStateException("second attempt reached the starter");
            });
        boolean retried;
        try {
            System.setProperty("java.home", temporaryDirectory.resolve("fake-runtime").toString());
            retried = (boolean) method("installPreparedUpdate").invoke(updater);
        } finally {
            System.setProperty("java.home", javaHome);
        }
        assertFalse(retried);
        assertEquals("second attempt reached the starter",
            ((UpdateTransaction) transactionField.get(updater)).failureMessage());
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

    @Test
    void reusesTheCachedPackageInsteadOfDownloadingItAgain() throws Exception {
        try (TrustedKey key = new TrustedKey()) {
            Fixture fixture = fixture(key);
            FullPackageDesktopUpdater updater = fixture.updater(key);
            Files.createDirectories(fixture.cachedPackage().getParent());
            Files.copy(fixture.packageFile(), fixture.cachedPackage(), StandardCopyOption.REPLACE_EXISTING);

            assertTrue(updater.appCheckUpdate().needsUpdate());
            assertTrue(updater.triggerDownload(new ConsoleResult()));
            assertTrue(updater.triggerDownload(new ConsoleResult()),
                "a repeated download request for a prepared update must keep reporting success");

            assertEquals(0, fixture.transport().downloads(),
                "a package that is already in the cache must not be downloaded again");
            assertTrue(Files.isRegularFile(fixture.layout().preparedUpdateFile()),
                "a prepared update must be remembered so a restart can install it");
            assertTrue(Files.exists(fixture.stagedCandidate()),
                "the prepared package must be staged for the helper");
        }
    }

    @Test
    void downloadsThePackageWhenTheCacheDoesNotMatchTheManifest() throws Exception {
        try (TrustedKey key = new TrustedKey()) {
            Fixture fixture = fixture(key);
            FullPackageDesktopUpdater updater = fixture.updater(key);
            Files.createDirectories(fixture.cachedPackage().getParent());
            Files.writeString(fixture.cachedPackage(), "truncated download");

            assertTrue(updater.appCheckUpdate().needsUpdate());
            assertTrue(updater.triggerDownload(new ConsoleResult()));

            assertEquals(1, fixture.transport().downloads());
            assertEquals(fixture.manifest().packageSha256(), sha256(fixture.cachedPackage()),
                "the cached package must be replaced by the verified download");
            assertTrue(Files.isRegularFile(fixture.layout().preparedUpdateFile()));
        }
    }

    @Test
    void keepsThePreparedUpdateAcrossUpdateChecks() throws Exception {
        try (TrustedKey key = new TrustedKey()) {
            Fixture fixture = fixture(key);
            FullPackageDesktopUpdater updater = fixture.updater(key);
            Files.createDirectories(fixture.cachedPackage().getParent());
            Files.copy(fixture.packageFile(), fixture.cachedPackage(), StandardCopyOption.REPLACE_EXISTING);
            assertTrue(updater.appCheckUpdate().needsUpdate());
            assertTrue(updater.triggerDownload(new ConsoleResult()));

            fixture.transport().disableJson();
            DesktopUpdateCheckResult check = updater.appCheckUpdate();

            assertEquals(DesktopUpdateCheckResult.State.READY_TO_INSTALL, check.state(),
                "the check must report the prepared update instead of running discovery");
            assertEquals(fixture.manifest().version(), check.version());
            assertTrue(check.needsUpdate(), "an update that waits for its installation is still pending");
            assertEquals(UpdatePhaseEnum.PRECHECKED,
                ((UpdateTransaction) field("preparedTransaction").get(updater)).phase());
        }
    }

    @Test
    void offersAPreparedUpdateForInstallationAfterARestartWithoutNetwork() throws Exception {
        try (TrustedKey key = new TrustedKey()) {
            Fixture fixture = fixture(key);
            FullPackageDesktopUpdater firstSession = fixture.updater(key);
            Files.createDirectories(fixture.cachedPackage().getParent());
            Files.copy(fixture.packageFile(), fixture.cachedPackage(), StandardCopyOption.REPLACE_EXISTING);
            assertTrue(firstSession.appCheckUpdate().needsUpdate());
            assertTrue(firstSession.triggerDownload(new ConsoleResult()));
            Path stagedByTheFirstSession = fixture.layout().stagingDirectory().resolve("staged-already");
            Files.writeString(stagedByTheFirstSession, "kept");

            StubTransport offline = new StubTransport();
            offline.disableJson();
            FullPackageDesktopUpdater secondSession = new FullPackageDesktopUpdater(
                fixture.layout(), "COMMUNITY", fixture.packageType(), offline,
                new UpdateDiscoveryService(offline,
                    new UpdateManifestVerifier(Map.of(TEST_KEY_ID, key.keyPair().getPublic())), BASE));

            DesktopUpdateCheckResult check = secondSession.appCheckUpdate();

            assertEquals(DesktopUpdateCheckResult.State.READY_TO_INSTALL, check.state(),
                "a downloaded update must survive a restart and must not need the network again");
            assertEquals(fixture.manifest().version(), check.version());
            assertEquals(0, offline.downloads());
            UpdateTransaction restored = (UpdateTransaction) field("preparedTransaction").get(secondSession);
            assertEquals(UpdatePhaseEnum.PRECHECKED, restored.phase());
            assertEquals(fixture.manifest().packageSha256(), restored.targetPackageSha256());
            assertTrue(Files.exists(stagedByTheFirstSession),
                "the staged package must be reused instead of being unpacked again");
        }
    }

    @Test
    void discardsAPreparedUpdateWhoseCachedPackageChanged() throws Exception {
        try (TrustedKey key = new TrustedKey()) {
            Fixture fixture = fixture(key);
            FullPackageDesktopUpdater firstSession = fixture.updater(key);
            Files.createDirectories(fixture.cachedPackage().getParent());
            Files.copy(fixture.packageFile(), fixture.cachedPackage(), StandardCopyOption.REPLACE_EXISTING);
            assertTrue(firstSession.appCheckUpdate().needsUpdate());
            assertTrue(firstSession.triggerDownload(new ConsoleResult()));
            Files.writeString(fixture.cachedPackage(), "tampered");

            FullPackageDesktopUpdater secondSession = fixture.updater(key);
            DesktopUpdateCheckResult check = secondSession.appCheckUpdate();

            assertEquals(DesktopUpdateCheckResult.State.AVAILABLE, check.state(),
                "a package that no longer matches its manifest must be downloaded again");
            assertNull(field("preparedTransaction").get(secondSession));
            assertFalse(Files.exists(fixture.layout().preparedUpdateFile()),
                "a discarded prepared update must not be offered again");
        }
    }

    @Test
    void forgetsAPreparedUpdateThatWasInstalledInTheMeantime() throws Exception {
        try (TrustedKey key = new TrustedKey()) {
            Fixture fixture = fixture(key);
            FullPackageDesktopUpdater firstSession = fixture.updater(key);
            Files.createDirectories(fixture.cachedPackage().getParent());
            Files.copy(fixture.packageFile(), fixture.cachedPackage(), StandardCopyOption.REPLACE_EXISTING);
            assertTrue(firstSession.appCheckUpdate().needsUpdate());
            assertTrue(firstSession.triggerDownload(new ConsoleResult()));

            // The prepared release is now the installed one, exactly like after a successful update.
            new ObjectMapper().writeValue(fixture.layout().appDirectory().resolve("version.json").toFile(),
                new InstalledAppVersion(fixture.manifest().version(), fixture.manifest().releaseEpoch(),
                    "installed"));

            FullPackageDesktopUpdater secondSession = fixture.updater(key);
            DesktopUpdateCheckResult check = secondSession.appCheckUpdate();

            assertFalse(check.state() == DesktopUpdateCheckResult.State.READY_TO_INSTALL,
                "a spent prepared update must not be offered for installation again");
            assertFalse(Files.exists(fixture.layout().preparedUpdateFile()));
            assertTrue(anyAuditLogContains(fixture.layout(), "event=PREPARED_UPDATE_CONSUMED"),
                "a spent prepared update is cleared without being reported as a problem");
        }
    }

    @Test
    void restoresAPreparedBetaUpdateAfterARestart() throws Exception {
        try (TrustedKey key = new TrustedKey()) {
            Fixture fixture = fixture(key);
            UpdateManifest betaManifest = signedManifest(key.keyPair(), RuntimePlatformDetector.platform(),
                RuntimePlatformDetector.architecture(), fixture.packageType(),
                Files.size(fixture.packageFile()), sha256(fixture.packageFile()), TEST_KEY_ID,
                UpdateChannelEnum.BETA);
            Files.createDirectories(fixture.cachedPackage().getParent());
            Files.copy(fixture.packageFile(), fixture.cachedPackage(), StandardCopyOption.REPLACE_EXISTING);
            new PreparedUpdateStore(fixture.layout()).save("tx-beta-restore", betaManifest);

            StubTransport offline = new StubTransport();
            offline.disableJson();
            FullPackageDesktopUpdater updater = new FullPackageDesktopUpdater(fixture.layout(), "COMMUNITY",
                fixture.packageType(), offline,
                new UpdateDiscoveryService(offline,
                    new UpdateManifestVerifier(Map.of(TEST_KEY_ID, key.keyPair().getPublic())), BASE));

            DesktopUpdateCheckResult check = updater.appCheckUpdate();

            assertEquals(DesktopUpdateCheckResult.State.READY_TO_INSTALL, check.state(),
                "a beta update that was downloaded must verify as beta in the next session");
            assertEquals(betaManifest.version(), check.version());
            assertEquals(0, offline.downloads());
        }
    }

    @Test
    void reportsAFailedCheckWhenTheUpdateSourceCannotBeReached() throws Exception {
        try (TrustedKey key = new TrustedKey()) {
            Fixture fixture = fixture(key);
            UpdateLayout layout = fixture.layout();
            Files.createDirectories(layout.supportRoot());
            new ObjectMapper().writeValue(layout.preferencesFile().toFile(), new UpdatePreferences(true));
            UpdatePlatformEnum platform = RuntimePlatformDetector.platform();
            UpdateArchitectureEnum architecture = RuntimePlatformDetector.architecture();
            String stableIndex = BASE + "stable/latest_version.json";
            String betaIndex = BASE + "beta/latest_version.json";
            String betaManifestUrl = BASE + "beta/5.3.4/manifest.json";
            StubTransport transport = new StubTransport();
            // The stable channel has no index yet while the beta channel cannot be reached, which is
            // exactly the situation a user with a broken update source is in.
            transport.failJson(stableIndex, missingResource(stableIndex));
            transport.put(betaIndex, new ReleaseIndex(
                2, 101, ReleaseStatusEnum.ACTIVE, UpdateChannelEnum.BETA,
                List.of(new ReleaseReference("5.3.4", platform, architecture,
                    fixture.packageType(), betaManifestUrl))
            ));
            transport.failJson(betaManifestUrl, unreachable(betaManifestUrl));
            FullPackageDesktopUpdater updater = new FullPackageDesktopUpdater(layout, "COMMUNITY",
                fixture.packageType(), transport,
                new UpdateDiscoveryService(transport,
                    new UpdateManifestVerifier(Map.of(TEST_KEY_ID, key.keyPair().getPublic())), BASE));

            DesktopUpdateCheckResult check = updater.appCheckUpdate();

            assertEquals(DesktopUpdateCheckResult.State.CHECK_FAILED, check.state(),
                "a channel that cannot be reached is not a channel without a release");
            assertFalse(check.needsUpdate(), "a failed check did not learn about any release");
            assertTrue(anyAuditLogContains(layout, "stage=DISCOVERY event=RESULT outcome=CHECK_FAILED"));
        }
    }

    @Test
    void reportsNoUpdateWhenTheReleaseIndexIsNotPublished() throws Exception {
        try (TrustedKey key = new TrustedKey()) {
            Fixture fixture = fixture(key);
            StubTransport transport = new StubTransport();
            String stableIndex = BASE + "stable/latest_version.json";
            transport.failJson(stableIndex, missingResource(stableIndex));
            FullPackageDesktopUpdater updater = new FullPackageDesktopUpdater(fixture.layout(), "COMMUNITY",
                fixture.packageType(), transport,
                new UpdateDiscoveryService(transport,
                    new UpdateManifestVerifier(Map.of(TEST_KEY_ID, key.keyPair().getPublic())), BASE));

            DesktopUpdateCheckResult check = updater.appCheckUpdate();

            assertEquals(DesktopUpdateCheckResult.State.NOT_AVAILABLE, check.state(),
                "a channel that has not published its index yet simply has no update");
            assertTrue(anyAuditLogContains(fixture.layout(), "stage=DISCOVERY event=RESULT outcome=NO_UPDATE"));
        }
    }

    private static IllegalStateException missingResource(String url) {
        return new IllegalStateException("Cannot fetch update metadata: " + url,
            new HttpsUpdateTransport.MissingUpdateResourceException("Update server returned HTTP 404"));
    }

    private static IllegalStateException unreachable(String url) {
        return new IllegalStateException("Cannot fetch update metadata: " + url,
            new java.net.ConnectException("HTTP connect timed out"));
    }

    private static boolean anyAuditLogContains(UpdateLayout layout, String expected) throws IOException {
        if (!Files.isDirectory(layout.logsDirectory())) {
            return false;
        }
        try (var files = Files.list(layout.logsDirectory())) {
            for (Path file : files.toList()) {
                if (Files.readString(file).contains(expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final String TEST_KEY_ID = "test-release-key";
    private static final String TEST_KEY_ID_PROPERTY = "chat2db.update.key-id";
    private static final String TEST_PUBLIC_KEY_PROPERTY = "chat2db.update.public-key";

    /** Makes the bundled-key verification path trust a key this test owns. */
    private static final class TrustedKey implements AutoCloseable {

        private final KeyPair keyPair;
        private final String previousKeyId;
        private final String previousPublicKey;

        TrustedKey() throws Exception {
            keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            previousKeyId = System.getProperty(TEST_KEY_ID_PROPERTY);
            previousPublicKey = System.getProperty(TEST_PUBLIC_KEY_PROPERTY);
            System.setProperty(TEST_KEY_ID_PROPERTY, TEST_KEY_ID);
            System.setProperty(TEST_PUBLIC_KEY_PROPERTY,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        }

        KeyPair keyPair() {
            return keyPair;
        }

        @Override
        public void close() {
            restore(TEST_KEY_ID_PROPERTY, previousKeyId);
            restore(TEST_PUBLIC_KEY_PROPERTY, previousPublicKey);
        }

        private static void restore(String name, String value) {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        }
    }

    private record Fixture(UpdateLayout layout, UpdateManifest manifest, Path packageFile,
            StubTransport transport, UpdatePackageTypeEnum packageType) {

        FullPackageDesktopUpdater updater(TrustedKey key) {
            return new FullPackageDesktopUpdater(layout, "COMMUNITY", packageType, transport,
                new UpdateDiscoveryService(transport,
                    new UpdateManifestVerifier(Map.of(TEST_KEY_ID, key.keyPair().getPublic())), BASE));
        }

        Path cachedPackage() {
            return layout.cachedPackage(packageType);
        }

        Path stagedCandidate() {
            return layout.stagedPackage(packageType);
        }
    }

    /** An online release that serves one package, so a download can always succeed. */
    private Fixture fixture(TrustedKey key) throws Exception {
        UpdateLayout layout = layout();
        UpdatePlatformEnum platform = RuntimePlatformDetector.platform();
        UpdatePackageTypeEnum packageType = directPackageType(platform);
        Path packageFile = packageFor(packageType);
        UpdateManifest manifest = signedManifest(key.keyPair(), platform,
            RuntimePlatformDetector.architecture(), packageType, Files.size(packageFile),
            sha256(packageFile), TEST_KEY_ID);
        StubTransport transport = new StubTransport();
        transport.setDownloadSource(packageFile);
        String manifestUrl = BASE + "stable/5.3.4/manifest.json";
        transport.put(BASE + "stable/latest_version.json", new ReleaseIndex(
            2, 101, ReleaseStatusEnum.ACTIVE, UpdateChannelEnum.STABLE,
            List.of(new ReleaseReference("5.3.4", platform, RuntimePlatformDetector.architecture(),
                packageType, manifestUrl))
        ));
        transport.put(manifestUrl, manifest);
        Files.createDirectories(layout.appDirectory());
        new ObjectMapper().writeValue(layout.appDirectory().resolve("version.json").toFile(),
            new InstalledAppVersion("5.3.3", 100, "installed"));
        return new Fixture(layout, manifest, packageFile, transport, packageType);
    }

    private Path packageFor(UpdatePackageTypeEnum packageType) throws Exception {
        if (packageType != UpdatePackageTypeEnum.MACOS_APP_ARCHIVE) {
            Path single = temporaryDirectory.resolve("package." + packageType.fileExtension());
            Files.writeString(single, "package-bytes");
            if (packageType == UpdatePackageTypeEnum.LINUX_APPIMAGE) {
                assertTrue(single.toFile().setExecutable(true, false));
            }
            return single;
        }
        Path source = temporaryDirectory.resolve("package-source");
        Path launcher = source.resolve("package/bin/chat2db");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "launcher");
        assertTrue(launcher.toFile().setExecutable(true, false));
        Path archive = temporaryDirectory.resolve("package.tar.gz");
        Process process = new ProcessBuilder("tar", "-czf", archive.toString(),
            "-C", source.toString(), "package").inheritIO().start();
        assertEquals(0, process.waitFor());
        return archive;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unsupported) {
            throw new IllegalStateException("SHA-256 is not available", unsupported);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = FullPackageDesktopUpdater.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static java.lang.reflect.Method method(String name, Class<?>... parameterTypes) throws Exception {
        java.lang.reflect.Method method = FullPackageDesktopUpdater.class.getDeclaredMethod(name, parameterTypes);
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
        return signedManifest(keyPair, platform, architecture, packageType, 100, "a".repeat(64), "release");
    }

    private static UpdateManifest signedManifest(KeyPair keyPair, UpdatePlatformEnum platform,
            UpdateArchitectureEnum architecture, UpdatePackageTypeEnum packageType, long packageSize,
            String packageSha256, String keyId) throws Exception {
        return signedManifest(keyPair, platform, architecture, packageType, packageSize, packageSha256, keyId,
            UpdateChannelEnum.STABLE);
    }

    private static UpdateManifest signedManifest(KeyPair keyPair, UpdatePlatformEnum platform,
            UpdateArchitectureEnum architecture, UpdatePackageTypeEnum packageType, long packageSize,
            String packageSha256, String keyId, UpdateChannelEnum channel) throws Exception {
        UpdateManifest unsigned = new UpdateManifest(
            2, 101, ReleaseStatusEnum.ACTIVE, "COMMUNITY", channel, "5.3.4", "5.3.401", "sha",
            platform, architecture, UpdateScopeEnum.FULL_PACKAGE, packageType,
            "https://cdn.example.com/package." + packageType.fileExtension(), packageSize, packageSha256,
            packageType.singleFile() ? "." : "bin/chat2db",
            3, 3, "https://example.com/notes", keyId, null
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
        private final Map<String, RuntimeException> jsonFailures = new HashMap<>();
        private final AtomicInteger downloads = new AtomicInteger();
        private Path downloadSource;
        private boolean jsonDisabled;

        void put(String url, Object value) {
            values.put(url, value);
        }

        void failJson(String url, RuntimeException failure) {
            jsonFailures.put(url, failure);
        }

        void setDownloadSource(Path downloadSource) {
            this.downloadSource = downloadSource;
        }

        void disableJson() {
            this.jsonDisabled = true;
        }

        int downloads() {
            return downloads.get();
        }

        @Override
        public <T> T getJson(String url, Class<T> type) {
            if (jsonDisabled) {
                throw new IllegalStateException("No update check may run while an update is prepared: " + url);
            }
            RuntimeException failure = jsonFailures.get(url);
            if (failure != null) {
                throw failure;
            }
            return type.cast(values.get(url));
        }

        @Override
        public Path download(String url, Path destination, long expectedSize, String expectedSha256,
                DownloadProgress listener) {
            downloads.incrementAndGet();
            if (downloadSource == null) {
                throw new UnsupportedOperationException("No download source is configured for " + url);
            }
            try {
                Files.createDirectories(destination.getParent());
                Files.copy(downloadSource, destination, StandardCopyOption.REPLACE_EXISTING);
                long size = Files.size(destination);
                listener.onBytes(size, expectedSize);
                if (size != expectedSize) {
                    throw new IllegalStateException("Update payload size mismatch: expected " + expectedSize
                        + ", got " + size);
                }
                if (!sha256(destination).equalsIgnoreCase(expectedSha256)) {
                    throw new IllegalStateException("Update payload SHA-256 mismatch");
                }
                return destination;
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot download update payload: " + url, exception);
            }
        }
    }
}
