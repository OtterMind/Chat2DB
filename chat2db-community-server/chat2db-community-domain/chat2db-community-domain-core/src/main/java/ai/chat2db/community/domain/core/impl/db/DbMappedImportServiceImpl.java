package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.model.db.ImportTargetColumn;
import ai.chat2db.community.domain.api.model.db.MappedImportExecution;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.domain.api.service.db.IDbMappedImportService;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.task.IImportTaskSubmissionService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DbMappedImportServiceImpl implements IDbMappedImportService {

    private final IDbImportPreviewService importPreviewService;

    private final IImportFileStagingService importFileStagingService;

    private final IImportTaskSubmissionService importTaskSubmissionService;

    public DbMappedImportServiceImpl(IDbImportPreviewService importPreviewService,
            IImportFileStagingService importFileStagingService,
            IImportTaskSubmissionService importTaskSubmissionService) {
        this.importPreviewService = importPreviewService;
        this.importFileStagingService = importFileStagingService;
        this.importTaskSubmissionService = importTaskSubmissionService;
    }

    @Override
    public Long submit(MappedImportExecution execution) {
        List<ImportColumnMapping> mappings = execution.getMappings();
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("At least one source column must be mapped");
        }
        validateUniqueMappings(mappings);
        File file = importFileStagingService.resolve(execution.getFileId());
        ImportPreview preview = importPreviewService.preview(execution.getDataSourceId(), execution.getDatabaseName(),
                execution.getSchemaName(), execution.getTableName(), file, execution.getCsvOptions());
        validateMappings(mappings, preview);
        UnmappedTargetStrategy strategy = execution.getUnmappedTarget() == null
                ? UnmappedTargetStrategy.DEFAULT : execution.getUnmappedTarget();
        validateRequiredColumns(mappings, preview, strategy);

        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType(TaskType.DATA_FILE_IMPORT.name())
                .taskName("Import " + preview.getTargetTableName())
                .target(TaskTargetSnapshot.builder()
                        .dataSourceId(execution.getDataSourceId())
                        .databaseName(execution.getDatabaseName())
                        .schemaName(execution.getSchemaName())
                        .tableName(preview.getTargetTableName())
                        .build())
                .sourceFile(file.getAbsolutePath())
                .importFileId(execution.getFileId())
                .displayFileName(file.getName())
                .format(extension(file.getName()))
                .csvOptions(execution.getCsvOptions())
                .columnMappings(mappings)
                .unmappedTarget(strategy)
                .build();
        return importTaskSubmissionService.submit(spec, execution.getFileId());
    }

    private static void validateUniqueMappings(List<ImportColumnMapping> mappings) {
        Set<String> sourceColumns = new HashSet<>();
        Set<String> targetColumns = new HashSet<>();
        for (ImportColumnMapping mapping : mappings) {
            if (mapping == null || !sourceColumns.add(normalizeColumn(mapping.getSourceColumn()))
                    || !targetColumns.add(normalizeColumn(mapping.getTargetColumn()))) {
                throw new IllegalArgumentException("Duplicate or invalid import column mapping");
            }
        }
    }

    private static void validateMappings(List<ImportColumnMapping> mappings, ImportPreview preview) {
        Set<String> sourceColumns = preview.getSourceColumns().stream()
                .map(DbMappedImportServiceImpl::normalizeColumn)
                .collect(Collectors.toSet());
        Set<String> targetColumns = preview.getTargetColumns().stream()
                .map(ImportTargetColumn::getName)
                .map(DbMappedImportServiceImpl::normalizeColumn)
                .collect(Collectors.toSet());
        for (ImportColumnMapping mapping : mappings) {
            if (!sourceColumns.contains(normalizeColumn(mapping.getSourceColumn()))
                    || !targetColumns.contains(normalizeColumn(mapping.getTargetColumn()))) {
                throw new IllegalArgumentException("Import column mapping does not match the preview");
            }
        }
    }

    private static void validateRequiredColumns(List<ImportColumnMapping> mappings, ImportPreview preview,
            UnmappedTargetStrategy strategy) {
        Set<String> mappedTargets = mappings.stream()
                .map(ImportColumnMapping::getTargetColumn)
                .map(DbMappedImportServiceImpl::normalizeColumn)
                .collect(Collectors.toSet());
        boolean missingRequiredColumn = preview.getTargetColumns().stream()
                .filter(column -> !column.isNullable() && !column.isAutoIncrement())
                .filter(column -> !mappedTargets.contains(normalizeColumn(column.getName())))
                .anyMatch(column -> strategy == UnmappedTargetStrategy.NULL || column.getDefaultValue() == null);
        if (missingRequiredColumn) {
            throw new IllegalArgumentException("Required import target column is not mapped");
        }
    }

    private static String normalizeColumn(String columnName) {
        if (StringUtils.isBlank(columnName)) {
            throw new IllegalArgumentException("Import column mapping must not be blank");
        }
        return columnName.toUpperCase(Locale.ROOT);
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
    }
}
