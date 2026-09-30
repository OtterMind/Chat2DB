package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportFinalizationOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportValidationOptions;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.community.domain.core.impl.task.imports.ImportFactory;
import ai.chat2db.community.domain.core.impl.task.imports.CsvManifestImporter;
import ai.chat2db.community.domain.core.impl.task.imports.CsvManifestPreparer;
import ai.chat2db.community.domain.core.impl.task.imports.ImportTaskSourceSupport;
import ai.chat2db.community.domain.core.impl.task.imports.StagingManifestImporter;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class DataFileImportTaskExecutor implements TaskExecutor<ImportTaskSpec> {

    @Autowired
    private IImportFileStagingService importFileStagingService;

    @Autowired
    private TaskStorage taskStorage;

    @Override
    public String taskType() {
        return TaskType.DATA_FILE_IMPORT.name();
    }

    @Override
    public Class<ImportTaskSpec> specType() {
        return ImportTaskSpec.class;
    }

    @Override
    public void execute(ImportTaskSpec spec, TaskExecutionContext context) {
        ImportManifest manifest = null;
        try {
            boolean scopedManifest = ImportTaskSourceSupport.isMultiTable(spec);
            List<ImportTableSource> sources = ImportTaskSourceSupport.effectiveSources(spec);
            String format;
            if (scopedManifest) {
                Set<String> formats = new LinkedHashSet<>();
                for (ImportTableSource source : sources) {
                    TaskExecutorSupport.requireReadableSource(source.getSourceFile());
                    formats.add(TaskExecutorSupport.requireFormat(source.getFormat()));
                }
                if (formats.size() != 1 || !formats.contains(TaskFileFormat.CSV.name())) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Schema/database data import currently requires CSV for every table source");
                }
                format = TaskFileFormat.CSV.name();
            } else {
                TaskExecutorSupport.requireReadableSource(spec.getSourceFile());
                format = TaskExecutorSupport.requireFormat(spec.getFormat());
            }
            if (TaskFileFormat.SQL.name().equals(format) || TaskFileFormat.ZIP.name().equals(format)) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Unsupported data import format");
            }
            context.reportProgress(5, TaskStage.READING.name(), "Preparing data import");
            boolean stagingRequested = stagingFeaturesRequested(spec);
            if (stagingRequested) {
                requireSupportedStagingRequest(spec, currentDatabaseType());
            }
            manifest = context.taskId() == null || taskStorage == null ? null
                    : taskStorage.loadImportManifest(context.taskId()).orElse(null);
            if (manifest == null && TaskFileFormat.CSV.name().equals(format)
                    && (TaskExecutionMode.isUltraFast(spec.getMode()) || scopedManifest || stagingRequested)) {
                manifest = new CsvManifestPreparer(taskStorage).prepare(spec, context);
            }
            if (manifest != null) {
                if (!TaskFileFormat.CSV.name().equals(format)
                        || (!TaskExecutionMode.isUltraFast(spec.getMode()) && !scopedManifest
                            && !stagingRequested)) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "A persisted import manifest requires an ultra-fast or scoped CSV task");
                }
                boolean stagingRequired = stagingRequested || StagingManifestImporter.required(spec, manifest);
                if (stagingRequired) {
                    requireSupportedStagingRequest(spec, currentDatabaseType());
                    new StagingManifestImporter().execute(spec, context, manifest);
                } else {
                    new CsvManifestImporter(taskStorage).execute(spec, context, manifest);
                }
                context.reportProgress(95, TaskStage.IMPORTING.name(), "Manifest import completed");
                return;
            }
            IImportStrategy strategy = ImportFactory.get(format);
            strategy.run(spec, context);
            context.reportProgress(95, TaskStage.IMPORTING.name(), "Data import completed");
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import data file", e);
        }
    }

    @Override
    public void cleanupTerminalResources(ImportTaskSpec spec, Long taskId) {
        Set<String> stagedFileIds = new LinkedHashSet<>();
        if (spec != null && StringUtils.isNotBlank(spec.getImportFileId())) {
            stagedFileIds.add(spec.getImportFileId());
        }
        if (spec != null && spec.getTableSources() != null) {
            spec.getTableSources().stream().filter(java.util.Objects::nonNull)
                    .map(ImportTableSource::getImportFileId).filter(StringUtils::isNotBlank)
                    .forEach(stagedFileIds::add);
        }
        RuntimeException cleanupFailure = null;
        for (String stagedFileId : stagedFileIds) {
            try {
                importFileStagingService.release(stagedFileId);
            } catch (RuntimeException releaseFailure) {
                cleanupFailure = recordCleanupFailure(cleanupFailure, releaseFailure);
            }
        }
        if (taskId != null && taskStorage != null) {
            try {
                taskStorage.loadImportManifest(taskId)
                        .ifPresent(manifest -> new CsvManifestPreparer(taskStorage).cleanup(manifest));
            } catch (RuntimeException manifestFailure) {
                cleanupFailure = recordCleanupFailure(cleanupFailure, manifestFailure);
            }
            try {
                taskStorage.clearResumeStates(taskId);
            } catch (RuntimeException stateFailure) {
                cleanupFailure = recordCleanupFailure(cleanupFailure, stateFailure);
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private static RuntimeException recordCleanupFailure(RuntimeException existing, RuntimeException next) {
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }

    static boolean stagingFeaturesRequested(ImportTaskSpec spec) {
        if (spec == null) {
            return false;
        }
        ImportValidationOptions validation = spec.getValidationOptions();
        ImportFinalizationOptions finalization = spec.getFinalizationOptions();
        String cycleStrategy = StringUtils.trimToEmpty(spec.getCycleStrategy());
        return StagingManifestImporter.requested(spec)
                || enabled(validation == null ? null : validation.getSourceProfiling())
                || enabled(validation == null ? null : validation.getRowCount())
                || enabled(validation == null ? null : validation.getChecksum())
                || enabled(validation == null ? null : validation.getOrphanCheck())
                || enabled(finalization == null ? null : finalization.getResetSequences())
                || enabled(finalization == null ? null : finalization.getRebuildIndexes())
                || enabled(finalization == null ? null : finalization.getRefreshStatistics())
                || StringUtils.isNotBlank(cycleStrategy) && !"REJECT".equalsIgnoreCase(cycleStrategy);
    }

    static boolean supportsStagingDatabase(String databaseType) {
        String normalized = StringUtils.defaultString(databaseType).toUpperCase(Locale.ROOT);
        return normalized.contains("MYSQL") || normalized.contains("MARIADB")
                || normalized.contains("POSTGRE") || normalized.contains("KINGBASE")
                || normalized.contains("H2");
    }

    static boolean supportsDeferredConstraints(String databaseType) {
        String normalized = StringUtils.defaultString(databaseType).toUpperCase(Locale.ROOT);
        return normalized.contains("POSTGRE") || normalized.contains("KINGBASE");
    }

    static void requireSupportedStagingRequest(ImportTaskSpec spec, String databaseType) {
        String normalized = StringUtils.defaultIfBlank(databaseType, "UNKNOWN").toUpperCase(Locale.ROOT);
        String cycleStrategy = StringUtils.defaultIfBlank(spec == null ? null : spec.getCycleStrategy(), "REJECT")
                .trim().toUpperCase(Locale.ROOT);
        if (!Set.of("REJECT", "DEFER_CONSTRAINTS", "STAGING_TWO_PHASE").contains(cycleStrategy)) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Unsupported import cycle strategy: " + cycleStrategy);
        }
        if (!supportsStagingDatabase(normalized)) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Advanced multi-table import is not supported for database type " + normalized);
        }
        if ("DEFER_CONSTRAINTS".equals(cycleStrategy) && !supportsDeferredConstraints(normalized)) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Deferred constraint import is not supported for database type " + normalized);
        }
    }

    private static boolean enabled(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static String currentDatabaseType() {
        return Chat2DBContext.getConnectInfo() == null ? null : Chat2DBContext.getConnectInfo().getDbType();
    }
}
