package ai.chat2db.community.updater;

import ai.chat2db.community.updater.v2.runtime.UpdateStartupCoordinator;
import ai.chat2db.community.updater.v2.audit.UpdateAuditLog;
import ai.chat2db.community.updater.v2.installation.FullPackageSwitcher;
import ai.chat2db.community.updater.v2.model.UpdateHealth;
import ai.chat2db.community.updater.v2.model.UpdateHelperPlan;
import ai.chat2db.community.updater.v2.installation.UpdateLayout;
import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePhaseEnum;
import ai.chat2db.community.updater.v2.model.UpdateTransaction;
import ai.chat2db.community.updater.v2.runtime.InstalledAppVersionReader;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class UpdateHelperMain {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration OLD_PROCESS_TIMEOUT = Duration.ofMinutes(2);

    private UpdateHelperMain() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: chat2db-updater <plan.json>");
            System.exit(2);
        }
        int exitCode;
        try {
            exitCode = run(Path.of(args[0]));
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static int run(Path planFile) throws Exception {
        UpdateHelperPlan plan = OBJECT_MAPPER.readValue(planFile.toFile(), UpdateHelperPlan.class);
        UpdateLayout layout = layout(plan);
        UpdateTransaction transaction = Objects.requireNonNull(plan.transaction(),
            "Updater helper plan is missing its transaction snapshot");
        if (transaction.transactionId() == null || transaction.transactionId().isBlank()) {
            throw new IllegalStateException("Updater helper plan transaction id is missing");
        }
        UpdateAuditLog audit = UpdateAuditLog.open(layout, plan.transactionId(), "HELPER");
        audit.versions(transaction.fromVersion(), transaction.toVersion());
        audit.critical("HANDOFF", "ACK", "helper accepted persisted plan");
        try {
            return execute(plan, layout, transaction, audit);
        } finally {
            removeHandoffLeftovers(plan, layout, audit);
        }
    }

    /**
     * Removes the plan this helper consumed, so a later run cannot replay it. The
     * agent file is kept: it is the registration the next update reuses, and it is
     * loaded without {@code RunAtLoad}.
     */
    private static void removeHandoffLeftovers(UpdateHelperPlan plan, UpdateLayout layout, UpdateAuditLog audit) {
        try {
            Path planFile = layout.workDirectory().resolve("plan.json");
            if (Files.deleteIfExists(planFile)) {
                audit.warn("HANDOFF", "PLAN_REMOVED", "the consumed helper plan was removed");
            }
        } catch (Exception planRemovalFailure) {
            audit.warn("HANDOFF", "PLAN_REMOVE_FAILED", failureMessage(planRemovalFailure));
        }
        // The agent file stays in place on purpose: it is the registration the next
        // update reuses, and RunAtLoad is disabled so it cannot run on its own.
    }

    private static int execute(UpdateHelperPlan plan, UpdateLayout layout,
            UpdateTransaction transaction, UpdateAuditLog audit) throws Exception {
        FullPackageSwitcher switcher = new FullPackageSwitcher(layout);
        NativePackageInstaller nativeInstaller = new NativePackageInstaller();
        Process trialProcess = null;
        Process normalProcess = null;
        Path healthFile = UpdateStartupCoordinator.healthFile(layout, plan.transactionId());
        try {
            audit.critical("QUIESCING", "WAIT_OLD_PROCESS", "pid=" + plan.oldProcessId());
            waitForOldProcess(plan.oldProcessId());
            audit.critical("QUIESCING", "OLD_PROCESS_EXITED", "pid=" + plan.oldProcessId());
            transaction = transition(transaction, UpdatePhaseEnum.READY_TO_SWITCH, audit);
            transaction = transition(transaction, UpdatePhaseEnum.SWITCHING, audit);
            if (plan.packageType().nativeInstaller()) {
                // A native installer can change package-manager state before returning a failure code.
                audit.critical("SWITCHING", "NATIVE_INSTALL_INTENT",
                    "packageType=" + plan.packageType());
                nativeInstaller.install(
                    plan.packageType(),
                    layout.stagedPackage(plan.packageType()),
                    layout.installTarget(),
                    plan.candidateLauncherRelativePath(),
                    audit
                );
                audit.critical("SWITCHING", "NATIVE_INSTALL_COMPLETE",
                    "packageType=" + plan.packageType());
            } else {
                audit.critical("SWITCHING", "DIRECT_SWITCH_INTENT",
                    "packageType=" + plan.packageType());
                ensureNoOtherInstance(plan, layout, audit);
                Path backup = switcher.switchToCandidate(plan.transactionId(), plan.packageType());
                audit.critical("SWITCHING", "DIRECT_SWITCH_COMPLETE",
                    "packageType=" + plan.packageType() + " backup=" + backup);
            }
            verifyInstalledTarget(layout, transaction, plan.packageType());
            verifyNativeInstalledLauncher(layout, plan);
            if (plan.packageType().nativeInstaller()) {
                audit.critical("SWITCHING", "INSTALL_RESULT",
                    "status=VERIFIED packageType=" + plan.packageType()
                        + " installRoot=" + layout.installTarget()
                        + " launcherRelativePath=" + plan.candidateLauncherRelativePath()
                        + " releaseVersion=" + transaction.toVersion()
                        + " releaseEpoch=" + transaction.releaseEpoch());
            }
            transaction = transition(transaction, UpdatePhaseEnum.STARTING_CANDIDATE, audit);
            Files.deleteIfExists(layout.updateWorkspace().resolve("health.json"));
            Files.deleteIfExists(healthFile);
            trialProcess = startApplication(plan, layout, LaunchMode.TRIAL);
            waitForHealthyProcess(plan, layout, trialProcess, UpdateHealth.TRIAL_HEALTHY);
            audit.critical("STARTING_CANDIDATE", "HEALTHY",
                "trial health confirmed pid=" + trialProcess.pid());
            transaction = transition(transaction, UpdatePhaseEnum.POSTCHECKING, audit);
            stopProcess(trialProcess);
            trialProcess = null;
            Files.deleteIfExists(healthFile);
            transaction = transition(transaction, UpdatePhaseEnum.RESTARTING_NORMAL, audit);
            normalProcess = startApplication(plan, layout, LaunchMode.NORMAL);
            audit.critical("RESTARTING_NORMAL", "PROCESS_STARTED",
                "pid=" + normalProcess.pid());
            waitForHealthyProcess(plan, layout, normalProcess, UpdateHealth.NORMAL_HEALTHY);
            audit.critical("RESTARTING_NORMAL", "HEALTHY",
                "normal health confirmed pid=" + normalProcess.pid());
            Files.deleteIfExists(healthFile);
            // The backup is only released once the relaunched application is healthy, so a
            // failed restart can still be rolled back to the previously installed package.
            try {
                switcher.commit(plan.transactionId());
            } catch (RuntimeException cleanupFailure) {
                audit.warn("POSTCHECKING", "CLEANUP_FAILED", cleanupFailure.getMessage());
            }
            transaction = transition(transaction, UpdatePhaseEnum.COMMITTED, audit);
            try {
                audit.status(UpdateAuditLog.STATUS_SUCCESS, UpdatePhaseEnum.COMMITTED.name(), "update committed");
            } catch (RuntimeException ignored) {
                // The installed normal application is already healthy and committed.
            }
            return 0;
        } catch (Exception failure) {
            audit.error(transaction.phase().name(), "UPDATE_FAILED", failure);
            stopFailedProcess(trialProcess, "STARTING_CANDIDATE", failure, audit);
            stopFailedProcess(normalProcess, "RESTARTING_NORMAL", failure, audit);
            if (!plan.packageType().nativeInstaller()) {
                // Also runs when the switch itself failed: it may have moved the installed
                // package aside before failing, and rollback() reports when no backup is left.
                rollbackToPreviousPackage(switcher, plan, layout, audit, failure);
            }
            persistTerminalFailure(transaction, failure, audit);
            return 1;
        }
    }

    /**
     * Restores and relaunches the package that was installed before this
     * transaction. Without it a candidate that never becomes healthy leaves the
     * user without a working application.
     */
    private static void rollbackToPreviousPackage(FullPackageSwitcher switcher, UpdateHelperPlan plan,
            UpdateLayout layout, UpdateAuditLog audit, Exception failure) {
        try {
            if (!switcher.rollback(plan.transactionId(), plan.packageType())) {
                audit.warn("ROLLING_BACK", "NO_BACKUP", "no previous package backup is available");
                return;
            }
            audit.critical("ROLLING_BACK", "RESTORED",
                "restored the previously installed package after " + failureMessage(failure));
            Process previous = startApplication(plan, layout, LaunchMode.PREVIOUS);
            audit.critical("ROLLING_BACK", "PROCESS_STARTED", "pid=" + previous.pid());
        } catch (Exception rollbackFailure) {
            audit.error("ROLLING_BACK", "ROLLBACK_FAILED", rollbackFailure);
            failure.addSuppressed(rollbackFailure);
        }
    }

    /**
     * Refuses to switch while another process still runs the installed
     * application. A second instance keeps the single-instance lock and makes the
     * trial candidate exit immediately, which is indistinguishable from a broken
     * package unless it is detected here.
     */
    static void ensureNoOtherInstance(UpdateHelperPlan plan, UpdateLayout layout, UpdateAuditLog audit) {
        List<String> launchCommand = candidateLaunchCommand(plan, layout);
        String expected = String.join(" ", launchCommand);
        if (expected.isBlank()) {
            audit.warn("QUIESCING", "INSTANCE_CHECK", "skipped=the application launcher command is unknown");
            return;
        }
        List<Long> running = ProcessHandle.allProcesses()
            .filter(handle -> handle.pid() != ProcessHandle.current().pid())
            .filter(handle -> handle.pid() != plan.oldProcessId())
            .filter(handle -> handle.info().commandLine()
                .map(line -> matchesLaunchCommand(line, expected))
                .orElse(false))
            .map(ProcessHandle::pid)
            .toList();
        if (!running.isEmpty()) {
            throw new IllegalStateException(
                "Another instance of the installed application is still running: " + running);
        }
        audit.critical("QUIESCING", "INSTANCE_CHECK", "no other instance is running");
    }

    private static boolean matchesLaunchCommand(String commandLine, String expected) {
        return commandLine.equals(expected) || commandLine.startsWith(expected + " ");
    }

    private static void persistTerminalFailure(UpdateTransaction transaction,
            Exception updateFailure, UpdateAuditLog audit) {
        String message = failureMessage(updateFailure);
        try {
            long failedAt = System.currentTimeMillis();
            audit.phase(transaction.phase(), UpdatePhaseEnum.FAILED,
                failedAt - transaction.updatedAtEpochMillis(), "INTENT");
            audit.state(transaction.fail(message, failedAt));
            audit.phase(transaction.phase(), UpdatePhaseEnum.FAILED,
                failedAt - transaction.updatedAtEpochMillis(), "COMMITTED");
            audit.status(UpdateAuditLog.STATUS_FAILED, UpdatePhaseEnum.FAILED.name(), message);
        } catch (Exception stateFailure) {
            audit.error(UpdatePhaseEnum.FAILED.name(), "STATE_PERSIST_FAILED", stateFailure);
        }
    }

    private static String failureMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static UpdateTransaction transition(UpdateTransaction transaction,
            UpdatePhaseEnum phase, UpdateAuditLog audit) {
        long now = System.currentTimeMillis();
        audit.phase(transaction.phase(), phase, now - transaction.updatedAtEpochMillis(), "INTENT");
        UpdateTransaction updated = transaction.transition(phase, now);
        audit.state(updated);
        audit.phase(transaction.phase(), phase, now - transaction.updatedAtEpochMillis(), "COMMITTED");
        return updated;
    }

    private static void verifyInstalledTarget(UpdateLayout layout, UpdateTransaction transaction,
            UpdatePackageTypeEnum packageType) {
        Path versionFile = layout.appDirectory().resolve("version.json");
        if (!Files.isRegularFile(versionFile)) {
            if (packageType == UpdatePackageTypeEnum.LINUX_APPIMAGE) {
                return;
            }
            throw new IllegalStateException("Installed target version metadata is missing: " + versionFile);
        }
        var installed = new InstalledAppVersionReader(layout).read();
        if (!transaction.toVersion().equals(installed.version())) {
            throw new IllegalStateException(
                "Installed target version mismatch: expected " + transaction.toVersion()
                    + " but found " + installed.version());
        }
        if (transaction.releaseEpoch() != installed.releaseEpoch()) {
            throw new IllegalStateException(
                "Installed target release epoch mismatch: expected " + transaction.releaseEpoch()
                    + " but found " + installed.releaseEpoch());
        }
    }

    private static Process startApplication(UpdateHelperPlan plan, UpdateLayout layout,
            LaunchMode mode) throws Exception {
        List<String> command = candidateLaunchCommand(plan, layout);
        if (command == null || command.isEmpty()) {
            throw new IllegalStateException("Application launcher command is missing");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        Path workingDirectory = Files.isDirectory(layout.installTarget())
            ? layout.installTarget()
            : layout.installTarget().getParent();
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put(
            UpdateStartupCoordinator.INSTALL_TARGET_ENV,
            layout.installTarget().toString()
        );
        builder.environment().put(
            UpdateStartupCoordinator.TARGET_VERSION_ENV,
            plan.transaction().toVersion()
        );
        if (mode == LaunchMode.TRIAL) {
            builder.environment().put(UpdateStartupCoordinator.TRANSACTION_ENV, plan.transactionId());
            builder.environment().remove(UpdateStartupCoordinator.NORMAL_TRANSACTION_ENV);
        } else if (mode == LaunchMode.NORMAL) {
            builder.environment().remove(UpdateStartupCoordinator.TRANSACTION_ENV);
            builder.environment().put(
                UpdateStartupCoordinator.NORMAL_TRANSACTION_ENV,
                plan.transactionId()
            );
        } else {
            // A restored previous package must not look like an update startup for a
            // transaction that has already failed.
            builder.environment().remove(UpdateStartupCoordinator.TRANSACTION_ENV);
            builder.environment().remove(UpdateStartupCoordinator.NORMAL_TRANSACTION_ENV);
            builder.environment().remove(UpdateStartupCoordinator.TARGET_VERSION_ENV);
        }
        return builder.start();
    }

    static Path appDirectory(UpdateHelperPlan plan) {
        Path installRoot = Path.of(plan.installRoot());
        if (plan.packageType() == UpdatePackageTypeEnum.MACOS_APP_ARCHIVE) {
            return installRoot.resolve("Contents/app");
        }
        return installRoot.resolve("app");
    }

    private static UpdateLayout layout(UpdateHelperPlan plan) {
        Path installRoot = Path.of(plan.installRoot());
        return new UpdateLayout(
            installRoot,
            appDirectory(plan),
            Path.of(plan.cacheRoot()),
            Path.of(plan.supportRoot())
        );
    }

    private static void waitForOldProcess(long processId) throws Exception {
        ProcessHandle handle = ProcessHandle.of(processId).orElse(null);
        if (handle == null || !handle.isAlive()) {
            return;
        }
        handle.onExit().get(OLD_PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private static void verifyNativeInstalledLauncher(UpdateLayout layout, UpdateHelperPlan plan) {
        if (!plan.packageType().nativeInstaller()
                || plan.packageType() == UpdatePackageTypeEnum.WINDOWS_EXE) {
            return;
        }
        String launcherPath = plan.candidateLauncherRelativePath();
        if (launcherPath == null || launcherPath.isBlank() || ".".equals(launcherPath)) {
            throw new IllegalStateException("Native package launcher path is missing");
        }
        Path launcher = layout.installTarget().resolve(launcherPath).normalize();
        if (!launcher.startsWith(layout.installTarget()) || !Files.isRegularFile(launcher)) {
            throw new IllegalStateException("Native package launcher is missing: " + launcher);
        }
    }

    static List<String> candidateLaunchCommand(UpdateHelperPlan plan, UpdateLayout layout) {
        if (plan.candidateLaunchCommand() != null && !plan.candidateLaunchCommand().isEmpty()) {
            return List.copyOf(plan.candidateLaunchCommand());
        }
        Path launcher = plan.packageType().singleFile()
            ? layout.installTarget()
            : layout.installTarget().resolve(plan.candidateLauncherRelativePath()).normalize();
        if (!launcher.startsWith(layout.installTarget()) || !Files.isRegularFile(launcher)) {
            throw new IllegalStateException("Installed candidate launcher is missing: " + launcher);
        }
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(launcher.toString());
        if (plan.candidateLaunchArguments() != null) {
            command.addAll(plan.candidateLaunchArguments());
        }
        return List.copyOf(command);
    }

    private static void stopProcess(Process process) throws Exception {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static void stopFailedProcess(Process process, String stage, Exception failure,
            UpdateAuditLog audit) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            stopProcess(process);
        } catch (Exception stopFailure) {
            failure.addSuppressed(stopFailure);
            audit.error(stage, "STOP_FAILED", stopFailure);
        }
    }

    private static void waitForHealthyProcess(UpdateHelperPlan plan, UpdateLayout layout, Process process,
            String expectedStatus) throws Exception {
        Path healthFile = UpdateStartupCoordinator.healthFile(layout, plan.transactionId());
        long startedAt = System.nanoTime();
        long deadline = startedAt + Duration.ofSeconds(plan.healthTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(healthFile)) {
                UpdateHealth health = OBJECT_MAPPER.readValue(healthFile.toFile(), UpdateHealth.class);
                if (plan.transactionId().equals(health.transactionId())
                        && plan.transaction().toVersion().equals(health.version())
                        && expectedStatus.equals(health.status())
                        && process.pid() == health.processId()) {
                    if (!process.isAlive()) {
                        throw new IllegalStateException(
                            "Application exited while reporting " + expectedStatus
                                + describeProcess(process, startedAt));
                    }
                    return;
                }
                throw new IllegalStateException("Application health marker is invalid for " + expectedStatus
                    + " (marker=" + health.status() + " version=" + health.version()
                    + " pid=" + health.processId() + ", expected pid=" + process.pid() + ")");
            }
            if (!process.isAlive()) {
                throw new IllegalStateException("Application exited before reporting " + expectedStatus
                    + describeProcess(process, startedAt));
            }
            Thread.sleep(200L);
        }
        throw new IllegalStateException("Application health confirmation timed out for " + expectedStatus
            + describeProcess(process, startedAt));
    }

    private static String describeProcess(Process process, long startedAtNanos) {
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        String exit = process.isAlive() ? "alive" : String.valueOf(process.exitValue());
        return " (pid=" + process.pid() + " exit=" + exit + " afterMs=" + elapsedMillis + ")";
    }

    private enum LaunchMode {
        TRIAL,
        NORMAL,
        /** Relaunch of the restored previous package: no update coordination at all. */
        PREVIOUS
    }

}
