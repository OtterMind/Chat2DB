package ai.chat2db.community.jcef.update.v2;

import ai.chat2db.community.updater.v2.audit.UpdateAuditLog;
import ai.chat2db.community.updater.v2.installation.FullPackageStager;
import ai.chat2db.community.updater.v2.model.InstalledAppVersion;
import ai.chat2db.community.updater.v2.runtime.InstalledAppVersionReader;
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
import ai.chat2db.community.updater.v2.model.UpdateTransaction;
import ai.chat2db.community.updater.v2.transport.UpdateTransport;
import ai.chat2db.community.updater.v2.installation.UpdateWorkspaceInitializer;
import ai.chat2db.community.jcef.update.DesktopRestartSupport;
import ai.chat2db.community.jcef.update.DesktopUpdateCheckResult;
import ai.chat2db.community.jcef.update.IDesktopUpdater;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.jcef.utils.SingleInstanceUtil;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FullPackageDesktopUpdater implements IDesktopUpdater {

    private static final int UPDATER_PROTOCOL_VERSION = 3;
    private static final int DEFAULT_HEALTH_TIMEOUT_SECONDS = 120;

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

    private UpdateDiscoveryService.PendingUpdate pendingUpdate;
    private UpdateTransaction preparedTransaction;
    private UpdateManifest preparedManifest;
    private UpdateAuditLog auditLog;
    private int lastLoggedProgressBucket = -1;
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
    }

    @Override
    public synchronized DesktopUpdateCheckResult appCheckUpdate() {
        beginAuditOperation();
        try {
            InstalledAppVersion installed = installedVersionReader.read();
            auditLog.versions(installed.version(), "");
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
            return new DesktopUpdateCheckResult(true, pendingUpdate.manifest().version());
        } catch (Exception exception) {
            pendingUpdate = null;
            auditLog.error("DISCOVERY", "FAILED", exception);
            auditLog.status(UpdateAuditLog.STATUS_CHECK_FAILED, "DISCOVERY", failureMessage(exception));
            return DesktopUpdateCheckResult.notAvailable();
        }
    }

    @Override
    public synchronized boolean triggerDownload(ConsoleResult consoleResult) {
        if (pendingUpdate == null || helperStarted) {
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
            transaction = transition(transaction, UpdatePhaseEnum.VERIFIED);
            transaction = transition(transaction, UpdatePhaseEnum.PRECHECKING);
            auditLog.info("PRECHECKING", "STAGE", "staging complete package for validation");
            packageStager.stage(packageFile, manifest, layout);
            transaction = transition(transaction, UpdatePhaseEnum.PRECHECKED);
            preparedTransaction = transaction;
            preparedManifest = manifest;
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
            preparedTransaction = transition(preparedTransaction, UpdatePhaseEnum.QUIESCING);
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
            auditLog.critical("HANDOFF", "INTENT",
                "helper plan persisted oldPid=" + ProcessHandle.current().pid());
            new ProcessBuilder(javaExecutable.toString(), "-jar", helperCopy.toString(), planFile.toString())
                .directory(workDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            auditLog.status(UpdateAuditLog.STATUS_PENDING, "HANDOFF", "helper process started");
            auditLog.critical("HANDOFF", "STARTED", "helper process started; application will exit");
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
        pendingUpdate = null;
        preparedTransaction = null;
        preparedManifest = null;
        helperStarted = false;
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
