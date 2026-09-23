package ai.chat2db.community.jcef.update.v2;

import ai.chat2db.community.updater.v2.audit.UpdateAuditLog;
import ai.chat2db.community.updater.v2.installation.FullPackageStager;
import ai.chat2db.community.updater.v2.installation.MacLaunchAgentHandoff;
import ai.chat2db.community.updater.v2.model.InstalledAppVersion;
import ai.chat2db.community.updater.v2.runtime.InstalledAppVersionReader;
import ai.chat2db.community.updater.v2.runtime.UpdateHelperAck;
import ai.chat2db.community.updater.v2.installation.RuntimePackageDetector;
import ai.chat2db.community.updater.v2.runtime.RuntimePlatformDetector;
import ai.chat2db.community.updater.v2.enums.UpdateChannelEnum;
import ai.chat2db.community.updater.v2.discovery.UpdateDiscoveryService;
import ai.chat2db.community.updater.v2.model.UpdateEnvironment;
import ai.chat2db.community.updater.v2.model.UpdateHelperPlan;
import ai.chat2db.community.updater.v2.installation.UpdateLayout;
import ai.chat2db.community.updater.v2.model.UpdateManifest;
import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePhaseEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePlatformEnum;
import ai.chat2db.community.updater.v2.model.UpdatePreferences;
import ai.chat2db.community.updater.v2.state.UpdatePreferencesStore;
import ai.chat2db.community.updater.v2.state.UpdateStateMachine;
import ai.chat2db.community.updater.v2.model.UpdateTransaction;
import ai.chat2db.community.updater.v2.state.PreparedUpdateStore;
import ai.chat2db.community.updater.v2.transport.HttpsUpdateTransport;
import ai.chat2db.community.updater.v2.transport.UpdateTransport;
import ai.chat2db.community.updater.v2.installation.UpdateWorkspaceInitializer;
import ai.chat2db.community.updater.v2.verification.TrustedUpdateKeys;
import ai.chat2db.community.updater.v2.verification.UpdateManifestVerifier;
import ai.chat2db.community.jcef.update.DesktopRestartSupport;
import ai.chat2db.community.jcef.update.DesktopUpdateCheckResult;
import ai.chat2db.community.jcef.update.IDesktopUpdater;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.jcef.utils.SingleInstanceUtil;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class FullPackageDesktopUpdater implements IDesktopUpdater {

    private static final int UPDATER_PROTOCOL_VERSION = 3;
    private static final int DEFAULT_HEALTH_TIMEOUT_SECONDS = 120;
    private static final Duration HELPER_ACK_TIMEOUT = Duration.ofSeconds(30L);

    private final UpdateLayout layout;
    private final String product;
    private final UpdatePackageTypeEnum packageType;
    private final InstalledAppVersionReader installedVersionReader;
    private final UpdatePreferencesStore preferencesStore;
    private final UpdateWorkspaceInitializer workspaceInitializer;
    private final UpdateTransport transport;
    private final UpdateDiscoveryService discoveryService;
    private final FullPackageStager packageStager;
    private final JcefUpdateProgressReporter progressReporter;
    private final ObjectMapper objectMapper;
    private final PreparedUpdateStore preparedUpdateStore;
    private final UpdateManifestVerifier manifestVerifier;

    private UpdateDiscoveryService.PendingUpdate pendingUpdate;
    private UpdateTransaction preparedTransaction;
    private UpdateManifest preparedManifest;
    private UpdateAuditLog auditLog;
    private int lastLoggedProgressBucket = -1;
    private HelperStarter helperStarter = this::startHelperProcess;
    private Duration helperAckTimeout = HELPER_ACK_TIMEOUT;
    private boolean helperStarted;

    public static FullPackageDesktopUpdater create(String product, String linuxPackageName,
            UpdateTransport transport, UpdateDiscoveryService discoveryService) {
        UpdatePlatformEnum platform = RuntimePlatformDetector.platform();
        UpdatePackageTypeEnum packageType = RuntimePackageDetector.packageType(platform, linuxPackageName);
        return new FullPackageDesktopUpdater(resolveLayout(platform, packageType, product), product,
            packageType, transport, discoveryService);
    }

    public FullPackageDesktopUpdater(UpdateLayout layout, String product, UpdatePackageTypeEnum packageType,
            UpdateTransport transport,
            UpdateDiscoveryService discoveryService) {
        this.layout = layout;
        this.product = product;
        this.packageType = packageType;
        this.installedVersionReader = new InstalledAppVersionReader(layout, ConfigUtils::getLocalVersion);
        this.preferencesStore = new UpdatePreferencesStore(layout);
        this.workspaceInitializer = new UpdateWorkspaceInitializer(layout);
        this.transport = transport;
        this.discoveryService = discoveryService;
        this.packageStager = new FullPackageStager();
        this.progressReporter = new JcefUpdateProgressReporter();
        this.objectMapper = new ObjectMapper();
        this.preparedUpdateStore = new PreparedUpdateStore(layout);
        this.manifestVerifier = new UpdateManifestVerifier(TrustedUpdateKeys.load());
    }

    @Override
    public String installedVersion() {
        try {
            return installedVersionReader.read().version();
        } catch (Exception exception) {
            // Reporting is best effort; a missing version file must not fail the update check path.
            return "";
        }
    }

    @Override
    public synchronized DesktopUpdateCheckResult appCheckUpdate() {
        beginAuditOperation();
        try {
            InstalledAppVersion installed = installedVersionReader.read();
            auditLog.versions(installed.version(), "");
            String preparedVersion = reusePreparedUpdate(installed);
            if (preparedVersion != null) {
                auditLog.critical("DISCOVERY", "READY_TO_INSTALL",
                    "version=" + preparedVersion + " prepared update reused, discovery skipped");
                auditLog.status(UpdateAuditLog.STATUS_AVAILABLE, "DISCOVERY", "prepared update is ready to install");
                return DesktopUpdateCheckResult.readyToInstall(preparedVersion);
            }
            boolean receiveBeta = preferencesStore.load().receiveBeta();
            auditLog.critical("DISCOVERY", "START",
                "installedVersion=" + installed.version()
                    + " installedEpoch=" + installed.releaseEpoch()
                    + " product=" + product
                    + " platform=" + RuntimePlatformDetector.platform()
                    + " architecture=" + RuntimePlatformDetector.architecture()
                    + " packageType=" + packageType
                    + " receiveBeta=" + receiveBeta);
            UpdateEnvironment environment = new UpdateEnvironment(
                installed.version(),
                installed.releaseEpoch(),
                product,
                UpdateChannelEnum.STABLE,
                RuntimePlatformDetector.platform(),
                RuntimePlatformDetector.architecture(),
                packageType,
                UPDATER_PROTOCOL_VERSION
            );
            pendingUpdate = discoveryService.discover(environment, receiveBeta, auditLog);
            if (pendingUpdate == null) {
                auditLog.status(UpdateAuditLog.STATUS_NO_UPDATE, "DISCOVERY", "no eligible release");
                return DesktopUpdateCheckResult.notAvailable();
            }
            UpdateManifest manifest = pendingUpdate.manifest();
            auditLog.versions(installed.version(), manifest.version());
            auditLog.critical("DISCOVERY", "SELECTED",
                "version=" + manifest.version()
                    + " releaseEpoch=" + manifest.releaseEpoch()
                    + " channel=" + manifest.channel()
                    + " nativeVersion=" + manifest.nativeVersion()
                    + " packageSha256=" + manifest.packageSha256()
                    + " packageBytes=" + manifest.packageSize()
                    + " packageUrl=" + UpdateAuditLog.auditUrl(manifest.packageUrl()));
            auditLog.status(UpdateAuditLog.STATUS_AVAILABLE, "DISCOVERY", "update available");
            return DesktopUpdateCheckResult.available(pendingUpdate.manifest().version());
        } catch (Exception exception) {
            // A failed check must not discard a discovered update or a staged package, and it must
            // not be reported as "no update available" either: the user has to be able to tell a
            // broken update source from a source that publishes no newer release.
            boolean releaseNotPublished = isReleaseNotPublished(exception);
            auditLog.error("DISCOVERY", "FAILED", exception);
            auditLog.status(
                releaseNotPublished ? UpdateAuditLog.STATUS_NO_UPDATE : UpdateAuditLog.STATUS_CHECK_FAILED,
                "DISCOVERY", failureMessage(exception));
            return releaseNotPublished
                ? DesktopUpdateCheckResult.notAvailable()
                : DesktopUpdateCheckResult.checkFailed();
        }
    }

    /**
     * Whether the check failed only because the update source does not publish a release index or
     * manifest yet. Every channel that was queried has to be missing, otherwise a real failure
     * would be hidden behind a channel that is simply empty.
     */
    private static boolean isReleaseNotPublished(Throwable failure) {
        if (!HttpsUpdateTransport.isMissingResource(failure)) {
            return false;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (!HttpsUpdateTransport.isMissingResource(suppressed)) {
                return false;
            }
        }
        return true;
    }

    private UpdateEnvironment environment(InstalledAppVersion installed, UpdateChannelEnum channel) {
        return new UpdateEnvironment(
            installed.version(),
            installed.releaseEpoch(),
            product,
            channel,
            RuntimePlatformDetector.platform(),
            RuntimePlatformDetector.architecture(),
            packageType,
            UPDATER_PROTOCOL_VERSION
        );
    }

    /**
     * Reports a downloaded update that is ready to install: either the one prepared in
     * this session or, after a restart, the one remembered on disk. The remembered
     * manifest is verified again with the bundled key and the cached package has to
     * match it, so a package that was tampered with is never offered for installation.
     */
    private String reusePreparedUpdate(InstalledAppVersion installed) {
        if (preparedTransaction != null && preparedManifest != null && !helperStarted) {
            return preparedManifest.version();
        }
        PreparedUpdateStore.PreparedUpdate remembered = preparedUpdateStore.load().orElse(null);
        if (remembered == null) {
            return null;
        }
        if (remembered.manifest().releaseEpoch() <= installed.releaseEpoch()) {
            // The remembered update was installed or superseded in the meantime: it is simply spent,
            // which is the normal outcome after a successful installation.
            auditLog.info("DISCOVERY", "PREPARED_UPDATE_CONSUMED",
                "preparedVersion=" + remembered.manifest().version()
                    + " installedVersion=" + installed.version());
            preparedUpdateStore.clear();
            return null;
        }
        try {
            UpdateManifest manifest = remembered.manifest();
            // The remembered manifest names its own channel: a beta update must verify as beta,
            // exactly like the discovery does for the channel it queried.
            manifestVerifier.verify(manifest, environment(installed, manifest.channel()));
            Path packageFile = layout.cachedPackage(manifest.packageType());
            if (!cachedPackageMatches(packageFile, manifest)) {
                throw new IllegalStateException("Prepared update package is missing or does not match its manifest");
            }
            packageStager.stage(packageFile, manifest, layout);
            long now = System.currentTimeMillis();
            preparedTransaction = new UpdateTransaction(
                remembered.transactionId(),
                installed.version(),
                manifest.version(),
                manifest.releaseEpoch(),
                manifest.packageSha256(),
                UpdatePhaseEnum.PRECHECKED,
                now,
                now,
                null
            );
            preparedManifest = manifest;
            auditLog.critical("DISCOVERY", "PREPARED_UPDATE_RESTORED",
                "transactionId=" + remembered.transactionId()
                    + " version=" + manifest.version()
                    + " releaseEpoch=" + manifest.releaseEpoch()
                    + " bytes=" + manifest.packageSize()
                    + " sha256=" + manifest.packageSha256());
            return manifest.version();
        } catch (Exception unusable) {
            auditLog.warn("DISCOVERY", "PREPARED_UPDATE_DISCARDED", failureMessage(unusable));
            preparedUpdateStore.clear();
            preparedTransaction = null;
            preparedManifest = null;
            return null;
        }
    }

    private static boolean cachedPackageMatches(Path packageFile, UpdateManifest manifest) {
        try {
            if (!Files.isRegularFile(packageFile) || Files.size(packageFile) != manifest.packageSize()) {
                return false;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(packageFile)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(manifest.packageSha256());
        } catch (Exception unreadable) {
            return false;
        }
    }

    @Override
    public synchronized boolean triggerDownload(ConsoleResult consoleResult) {
        if (helperStarted) {
            return false;
        }
        if (preparedTransaction != null && preparedManifest != null) {
            // Already downloaded and staged: report success instead of downloading again. The
            // client still has to learn that the update is ready, so it gets the same completion
            // the download path reports.
            ensureAuditOperation();
            auditLog.status(UpdateAuditLog.STATUS_PENDING, "DOWNLOADING", "package is already prepared");
            progressReporter.completed(consoleResult);
            return true;
        }
        if (pendingUpdate == null) {
            return false;
        }
        InstalledAppVersion installed = installedVersionReader.read();
        UpdateManifest manifest = pendingUpdate.manifest();
        ensureAuditOperation();
        String transactionId = auditLog.operationId();
        UpdateTransaction transaction = UpdateTransaction.create(
            transactionId,
            installed.version(),
            manifest,
            System.currentTimeMillis()
        );
        try {
            workspaceInitializer.ensureReady(RuntimePlatformDetector.platform());
            auditLog.state(transaction);
            auditLog.critical("DISCOVERED", "TRANSACTION_CREATED",
                "transaction state persisted packageSha256=" + manifest.packageSha256());
            transaction = transition(transaction, UpdatePhaseEnum.DOWNLOADING);
            Path packageFile = layout.cachedPackage(manifest.packageType());
            lastLoggedProgressBucket = -1;
            progressReporter.reset();
            auditLog.critical("DOWNLOADING", "REQUEST",
                "url=" + UpdateAuditLog.auditUrl(manifest.packageUrl())
                    + " expectedBytes=" + manifest.packageSize()
                    + " expectedSha256=" + manifest.packageSha256());
            if (cachedPackageMatches(packageFile, manifest)) {
                auditLog.critical("DOWNLOADING", "CACHE_HIT",
                    "bytes=" + manifest.packageSize() + " sha256=" + manifest.packageSha256()
                        + " reused the package from an earlier download");
            } else {
                Files.deleteIfExists(packageFile);
                transport.download(
                    manifest.packageUrl(),
                    packageFile,
                    manifest.packageSize(),
                    manifest.packageSha256(),
                    (downloaded, total) -> {
                        progressReporter.progress(consoleResult, downloaded, total);
                        logDownloadProgress(downloaded, total);
                    }
                );
                auditLog.critical("DOWNLOADING", "COMPLETE",
                    "bytes=" + manifest.packageSize() + " sha256=" + manifest.packageSha256());
            }
            transaction = transition(transaction, UpdatePhaseEnum.VERIFIED);
            transaction = transition(transaction, UpdatePhaseEnum.PRECHECKING);
            auditLog.info("PRECHECKING", "STAGE", "staging complete package for validation");
            packageStager.stage(packageFile, manifest, layout);
            transaction = transition(transaction, UpdatePhaseEnum.PRECHECKED);
            preparedTransaction = transaction;
            preparedManifest = manifest;
            preparedUpdateStore.save(transaction.transactionId(), manifest);
            progressReporter.completed(consoleResult);
            return true;
        } catch (Exception exception) {
            auditLog.error(transaction.phase().name(), "FAILED", exception);
            try {
                long failedAt = System.currentTimeMillis();
                auditLog.phase(transaction.phase(), UpdatePhaseEnum.FAILED,
                    failedAt - transaction.updatedAtEpochMillis(), "INTENT");
                auditLog.state(transaction.fail(exception.getMessage(), failedAt));
                auditLog.phase(transaction.phase(), UpdatePhaseEnum.FAILED,
                    failedAt - transaction.updatedAtEpochMillis(), "COMMITTED");
            } catch (Exception stateFailure) {
                exception.addSuppressed(stateFailure);
            }
            auditLog.status(UpdateAuditLog.STATUS_FAILED, transaction.phase().name(), failureMessage(exception));
            progressReporter.failed(consoleResult);
            return false;
        }
    }

    @Override
    public synchronized boolean triggerInstallation() {
        return SingleInstanceUtil.guardExit(this::installPreparedUpdate).getAsBoolean();
    }

    private boolean installPreparedUpdate() {
        if (preparedTransaction == null || preparedManifest == null || helperStarted) {
            return false;
        }
        try {
            workspaceInitializer.ensureReady(RuntimePlatformDetector.platform());
            preparedTransaction = prepareForHandoff(preparedTransaction);
            auditLog.critical("HANDOFF", "PREPARE", "preparing updater helper runtime and plan");
            Path workDirectory = layout.workDirectory();
            Files.createDirectories(workDirectory);
            Path helperRuntime = workDirectory.resolve("helper-runtime");
            copyRuntime(Path.of(System.getProperty("java.home")), helperRuntime);
            Path helperSource = layout.appDirectory().resolve("tools/chat2db-updater.jar");
            Path helperCopy = workDirectory.resolve("chat2db-updater.jar");
            if (!Files.isRegularFile(helperSource)) {
                throw new IllegalStateException("Packaged update helper is missing: " + helperSource);
            }
            copyHelper(helperSource, helperCopy);
            Path planFile = workDirectory.resolve("plan.json");
            UpdateHelperPlan plan = new UpdateHelperPlan(
                preparedTransaction,
                layout.installRoot().toString(),
                product,
                layout.cacheRoot().toString(),
                layout.supportRoot().toString(),
                ProcessHandle.current().pid(),
                preparedManifest.packageType(),
                preparedManifest.launcherRelativePath(),
                List.of(),
                currentLaunchCommand(),
                DEFAULT_HEALTH_TIMEOUT_SECONDS
            );
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(planFile.toFile(), plan);
            Path javaExecutable = Path.of(
                helperRuntime.toString(),
                "bin",
                RuntimePlatformDetector.platform() == UpdatePlatformEnum.WINDOWS ? "java.exe" : "java"
            );
            Path helperStdout = workDirectory.resolve("helper-stdout.log");
            Path helperStderr = workDirectory.resolve("helper-stderr.log");
            auditLog.critical("HANDOFF", "INTENT",
                "helper plan persisted oldPid=" + ProcessHandle.current().pid());
            List<String> helperCommand = List.of(
                javaExecutable.toString(), "-jar", helperCopy.toString(), planFile.toString());
            Path auditLogFile = layout.auditLogFile(preparedTransaction.transactionId());
            long ackOffset = UpdateHelperAck.offset(auditLogFile);
            Runnable helperAbort = helperStarter.start(helperCommand, workDirectory, helperStdout,
                helperStderr);
            boolean helperAcknowledged = UpdateHelperAck.awaitSince(
                auditLogFile, ackOffset, helperAckTimeout);
            auditLog.critical("HANDOFF", "ACK_WAIT",
                "helperAck=" + helperAcknowledged
                    + " stdoutTail=" + logTail(helperStdout)
                    + " stderrTail=" + logTail(helperStderr));
            if (!helperAcknowledged) {
                abortHelper(helperAbort);
                throw new IllegalStateException("Update helper did not acknowledge the persisted plan within "
                    + helperAckTimeout.toSeconds() + "s");
            }
            auditLog.status(UpdateAuditLog.STATUS_PENDING, "HANDOFF", "helper process started");
            auditLog.critical("HANDOFF", "STARTED", "helper accepted the plan; application will exit");
            helperStarted = true;
            DesktopRestartSupport.exitCurrentProcessAfterResponse();
            return true;
        } catch (Exception exception) {
            ensureAuditOperation();
            auditLog.error("HANDOFF", "FAILED", exception);
            try {
                long failedAt = System.currentTimeMillis();
                UpdatePhaseEnum failedFrom = preparedTransaction.phase();
                long elapsed = failedAt - preparedTransaction.updatedAtEpochMillis();
                auditLog.phase(failedFrom, UpdatePhaseEnum.FAILED, elapsed, "INTENT");
                preparedTransaction = preparedTransaction.fail(exception.getMessage(), failedAt);
                auditLog.state(preparedTransaction);
                auditLog.phase(failedFrom, UpdatePhaseEnum.FAILED, elapsed, "COMMITTED");
            } catch (Exception stateFailure) {
                exception.addSuppressed(stateFailure);
            }
            auditLog.status(UpdateAuditLog.STATUS_FAILED, "HANDOFF", failureMessage(exception));
            return false;
        }
    }

    @Override
    public synchronized boolean prepareRestart() throws Exception {
        if (helperStarted) {
            return false;
        }
        return DesktopRestartSupport.prepareRestart();
    }

    @Override
    public void exitCurrentProcessAfterResponse() {
        DesktopRestartSupport.exitCurrentProcessAfterResponse();
    }

    @Override
    public synchronized boolean setBetaEnabled(boolean enabled) {
        try {
            workspaceInitializer.ensureReady(RuntimePlatformDetector.platform());
            preferencesStore.save(new UpdatePreferences(enabled));
            return true;
        } catch (Exception exception) {
            ensureAuditOperation();
            auditLog.error("PREFERENCES", "SAVE_FAILED", exception);
            return false;
        }
    }

    @Override
    public synchronized boolean isBetaEnabled() {
        return preferencesStore.load().receiveBeta();
    }

    private UpdateTransaction transition(UpdateTransaction transaction, UpdatePhaseEnum phase) {
        ensureAuditOperation();
        long now = System.currentTimeMillis();
        auditLog.phase(transaction.phase(), phase, now - transaction.updatedAtEpochMillis(), "INTENT");
        UpdateTransaction updated = transaction.transition(phase, now);
        auditLog.state(updated);
        auditLog.phase(transaction.phase(), phase, now - transaction.updatedAtEpochMillis(), "COMMITTED");
        return updated;
    }

    private void beginAuditOperation() {
        if (auditLog != null && pendingUpdate != null && preparedTransaction == null && !helperStarted) {
            auditLog.status(UpdateAuditLog.STATUS_SUPERSEDED, "DISCOVERY", "new update check started");
        }
        // A downloaded and staged update survives a new check: discarding it would make the
        // install step fail and would download the same package again.
        if (preparedTransaction == null) {
            pendingUpdate = null;
        }
        auditLog = UpdateAuditLog.begin(layout, UUID.randomUUID().toString(), "APPLICATION");
    }

    private void ensureAuditOperation() {
        if (auditLog == null) {
            auditLog = UpdateAuditLog.begin(layout, UUID.randomUUID().toString(), "APPLICATION");
        }
    }

    private void logDownloadProgress(long downloaded, long total) {
        int percent = total <= 0 ? 0 : (int)Math.min(100L, downloaded * 100L / total);
        int bucket = percent / 10;
        if (bucket <= lastLoggedProgressBucket && downloaded < total) {
            return;
        }
        lastLoggedProgressBucket = bucket;
        auditLog.info("DOWNLOADING", "PROGRESS",
            "downloadedBytes=" + downloaded + " totalBytes=" + total + " percent=" + percent);
    }

    private static String failureMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank()
            ? failure == null ? "unknown" : failure.getClass().getSimpleName()
            : message;
    }

    private List<String> currentLaunchCommand() {
        if (packageType == UpdatePackageTypeEnum.LINUX_APPIMAGE) {
            return List.of(layout.installTarget().toString());
        }
        ProcessHandle.Info info = ProcessHandle.current().info();
        String command = info.command()
            .orElseThrow(() -> new IllegalStateException("Current application launcher path is unavailable"));
        List<String> result = new ArrayList<>();
        result.add(command);
        result.addAll(List.of(info.arguments().orElse(new String[0])));
        return List.copyOf(result);
    }

    private static UpdateLayout resolveLayout(UpdatePlatformEnum platform, UpdatePackageTypeEnum packageType, String product) {
        Path currentDirectory = Path.of(OSOperateUtil.getCurrentJarPath()).toAbsolutePath().normalize();
        if (currentDirectory.getFileName() != null && "runtime".equals(currentDirectory.getFileName().toString())) {
            currentDirectory = currentDirectory.getParent();
        }
        String configuredRoot = System.getProperty("chat2db.install.root");
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return new UpdateLayout(Path.of(configuredRoot), currentDirectory,
                product);
        }
        return UpdateLayout.fromCurrentAppDirectory(
            currentDirectory,
            platform,
            packageType,
            product
        );
    }

    /**
     * Loads the helper as a per-transaction LaunchAgent on macOS. A helper spawned
     * as a plain child of this application is reclaimed together with the
     * application, which exits right after the handoff and kills the helper before
     * its JVM has started. The agent is loaded without {@code RunAtLoad} and started
     * with {@code kickstart}, so a plist that survives a crash cannot replay the
     * plan at the next login.
     */
    /**
     * The first attempt moves the transaction to QUIESCING. A retry after a failed
     * handoff has to resume from the phase the previous attempt reached: FAILED is
     * terminal in the update state machine, so transitioning again would reject the
     * retry the user just asked for while the application is still running.
     */
    private UpdateTransaction prepareForHandoff(UpdateTransaction transaction) {
        if (UpdateStateMachine.isTerminal(transaction.phase())) {
            auditLog.warn("HANDOFF", "RETRY",
                "retrying transaction " + transaction.transactionId()
                    + " after " + transaction.phase());
            return new UpdateTransaction(
                transaction.transactionId(),
                transaction.fromVersion(),
                transaction.toVersion(),
                transaction.releaseEpoch(),
                transaction.targetPackageSha256(),
                UpdatePhaseEnum.QUIESCING,
                transaction.createdAtEpochMillis(),
                System.currentTimeMillis(),
                null
            );
        }
        if (transaction.phase() == UpdatePhaseEnum.QUIESCING) {
            auditLog.warn("HANDOFF", "RETRY", "the helper never acknowledged; handing over again");
            return transaction;
        }
        return transition(transaction, UpdatePhaseEnum.QUIESCING);
    }

    private Runnable startHelperProcess(List<String> helperCommand, Path workDirectory, Path stdout,
            Path stderr) throws Exception {
        if (RuntimePlatformDetector.platform() != UpdatePlatformEnum.MACOS) {
            return startHelperDirectly(helperCommand, workDirectory, stdout);
        }
        return withDirectFallback(
            () -> startHelperAsLaunchAgent(helperCommand, workDirectory, stdout, stderr),
            () -> startHelperDirectly(helperCommand, workDirectory, stdout),
            agentFailure -> auditLog.warn("HANDOFF", "AGENT_FALLBACK",
                failureMessage(agentFailure) + "; starting the helper directly instead"));
    }

    /**
     * Starts the helper as a child of this application and returns the action that
     * ends it again. The abort matters on the direct path: a helper that never
     * acknowledged keeps waiting for this process to exit and would otherwise
     * perform the switch after the user was told the update failed.
     */
    private Runnable startHelperDirectly(List<String> helperCommand, Path workDirectory, Path stdout)
            throws IOException {
        Process helper = new ProcessBuilder(helperCommand)
            .directory(workDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(stdout.toFile())
            .start();
        return () -> {
            if (helper.isAlive()) {
                helper.destroyForcibly();
            }
        };
    }

    /**
     * Uses the launch agent when it can be loaded and falls back to starting the
     * helper directly. A device where launchd refuses the agent (restricted
     * session, managed policy) then keeps the previous behaviour instead of
     * losing the update, and the handoff still waits for the helper to
     * acknowledge, so a helper that dies with the application fails visibly.
     */
    static Runnable withDirectFallback(HelperLaunch agentLaunch, HelperLaunch directLaunch,
            Consumer<Exception> onFallback) {
        try {
            return agentLaunch.start();
        } catch (Exception agentFailure) {
            try {
                onFallback.accept(agentFailure);
            } catch (RuntimeException ignored) {
                // Reporting the fallback must not prevent the fallback itself.
            }
            try {
                return directLaunch.start();
            } catch (Exception directFailure) {
                directFailure.addSuppressed(agentFailure);
                throw new IllegalStateException("Cannot start the update helper", directFailure);
            }
        }
    }

    private void discardAgent(MacLaunchAgentHandoff handoff) {
        try {
            handoff.bootout(product);
        } catch (Exception bootoutFailure) {
            auditLog.warn("HANDOFF", "ABORT_FAILED", failureMessage(bootoutFailure));
        }
    }

    private void abortHelper(Runnable helperAbort) {
        if (helperAbort == null) {
            return;
        }
        try {
            helperAbort.run();
        } catch (RuntimeException abortFailure) {
            auditLog.warn("HANDOFF", "ABORT_FAILED", failureMessage(abortFailure));
        }
    }

    private Runnable startHelperAsLaunchAgent(List<String> helperCommand, Path workDirectory,
            Path stdout, Path stderr) throws Exception {
        MacLaunchAgentHandoff handoff = MacLaunchAgentHandoff.forCurrentUser(
            Path.of(System.getProperty("user.home")), MacLaunchAgentHandoff.processRunner());
        int bootstrapExit = handoff.bootstrap(product, helperCommand, workDirectory, stdout, stderr);
        auditLog.critical("HANDOFF", "BOOTSTRAP",
            "launchctl bootstrap/kickstart exit=" + bootstrapExit
                + " agent=" + handoff.agentFile(product)
                + " stdout=" + stdout);
        if (bootstrapExit != 0) {
            discardAgent(handoff);
            throw new IllegalStateException("Cannot load the update helper agent: exit=" + bootstrapExit);
        }
        return () -> {
            try {
                int bootoutExit = handoff.bootout(product);
                if (bootoutExit != 0) {
                    auditLog.warn("HANDOFF", "ABORT_EXIT", "launchctl bootout exit=" + bootoutExit);
                }
            } catch (Exception bootoutFailure) {
                auditLog.warn("HANDOFF", "ABORT_FAILED", failureMessage(bootoutFailure));
            }
        };
    }

    /** A launch attempt that either returns a cleanup action or throws. */
    @FunctionalInterface
    interface HelperLaunch {
        Runnable start() throws Exception;
    }

    /**
     * Starts the prepared helper. The handoff owns the wait for the helper's
     * acknowledgement, so a starter that cannot be observed is a failed handoff.
     */
    interface HelperStarter {
        /**
         * @return an action that unloads a helper that never acknowledged, or
         *         null when the started helper needs no cleanup.
         */
        Runnable start(List<String> helperCommand, Path workDirectory, Path stdout, Path stderr)
            throws Exception;
    }

    private static String logTail(Path logFile) {
        try {
            if (!Files.isRegularFile(logFile)) {
                return "<no output>";
            }
            String text = Files.readString(logFile).replace('\n', '|');
            return text.length() <= 800 ? text : text.substring(text.length() - 800);
        } catch (Exception unreadable) {
            return "<unreadable " + unreadable.getClass().getSimpleName() + ">";
        }
    }

    private static void copyRuntime(Path source, Path target) throws Exception {
        if (!Files.isDirectory(source)) {
            throw new IllegalStateException("Current Java runtime is missing: " + source);
        }
        deleteRecursively(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws java.io.IOException {
                Path destination = target.resolve(source.relativize(directory));
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws java.io.IOException {
                Path destination = target.resolve(source.relativize(file));
                if (Files.isSymbolicLink(file)) {
                    Files.createSymbolicLink(destination, Files.readSymbolicLink(file));
                } else {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyHelper(Path source, Path target) throws IOException {
        clearReadOnly(target);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        clearReadOnly(target);
    }

    private void clearReadOnly(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        DosFileAttributeView attributes = Files.getFileAttributeView(
            target, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes == null) {
            return;
        }
        try {
            if (!attributes.readAttributes().isReadOnly()) {
                return;
            }
            auditLog.warn("HANDOFF", "READ_ONLY_DETECTED", "path=" + target);
            attributes.setReadOnly(false);
            auditLog.critical("HANDOFF", "READ_ONLY_CLEARED", "path=" + target);
        } catch (IOException exception) {
            auditLog.warn("HANDOFF", "READ_ONLY_CLEAR_FAILED",
                "path=" + target + " reason=" + failureMessage(exception));
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
