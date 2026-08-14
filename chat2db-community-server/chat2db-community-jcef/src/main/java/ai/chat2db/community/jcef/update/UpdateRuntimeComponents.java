package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Immutable infrastructure dependencies used by one {@link UpdateWorkflow}. This is an explicit
 * parameter object, not a service locator: every dependency remains visible at the composition
 * root and is available only within the updater package.
 */
record UpdateRuntimeComponents(
        UpdateOperationCoordinator coordinator,
        UpdateChecker checker,
        ManifestValidator manifestValidator,
        UpdatePlanner planner,
        ResumablePayloadDownloader downloader,
        LocalVersionStore localVersionStore,
        UpdateBackupStore backupStore,
        InstallationExecutor inProcessInstallationExecutor,
        InstallationExecutor elevatedWindowsInstallationExecutor,
        UpdateSource updateSource,
        ObjectMapper objectMapper,
        Path appDirectory,
        LongSupplier nanosClock,
        Supplier<UpdateWorkflow.ProgressReporter> progressReporterFactory,
        Consumer<Exception> errorLogger,
        Consumer<String> warningLogger
) {
    UpdateRuntimeComponents {
        Objects.requireNonNull(coordinator, "coordinator is required");
        Objects.requireNonNull(checker, "checker is required");
        Objects.requireNonNull(manifestValidator, "manifestValidator is required");
        Objects.requireNonNull(planner, "planner is required");
        Objects.requireNonNull(downloader, "downloader is required");
        Objects.requireNonNull(localVersionStore, "localVersionStore is required");
        Objects.requireNonNull(backupStore, "backupStore is required");
        Objects.requireNonNull(inProcessInstallationExecutor, "inProcessInstallationExecutor is required");
        Objects.requireNonNull(elevatedWindowsInstallationExecutor, "elevatedWindowsInstallationExecutor is required");
        Objects.requireNonNull(updateSource, "updateSource is required");
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        Objects.requireNonNull(appDirectory, "appDirectory is required");
        Objects.requireNonNull(nanosClock, "nanosClock is required");
        Objects.requireNonNull(progressReporterFactory, "progressReporterFactory is required");
        Objects.requireNonNull(errorLogger, "errorLogger is required");
        Objects.requireNonNull(warningLogger, "warningLogger is required");
    }
}
