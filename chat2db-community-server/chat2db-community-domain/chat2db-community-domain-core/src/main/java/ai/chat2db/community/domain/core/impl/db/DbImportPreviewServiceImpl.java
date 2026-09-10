package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.model.db.ImportTargetColumn;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Database-independent import preview. File parsing is delegated by format; this service
 * resolves target metadata, suggests mappings, and assembles a bounded preview.
 */
@Service
public class DbImportPreviewServiceImpl implements IDbImportPreviewService {

    private static final int PREVIEW_ROW_LIMIT = 10;

    private final ImportPreviewFileParser fileParser;

    public DbImportPreviewServiceImpl(ImportPreviewFileParser fileParser) {
        this.fileParser = fileParser;
    }

    @Override
    public ImportPreview preview(Long dataSourceId, String databaseName, String schemaName,
                                 String tableName, File file) {
        return preview(dataSourceId, databaseName, schemaName, tableName, file, null);
    }

    @Override
    public ImportPreview preview(Long dataSourceId, String databaseName, String schemaName,
                                 String tableName, File file, CsvOptions csvOptions) {
        ImportPreviewFileParser.ParsedRows parsedRows = fileParser.parse(file, PREVIEW_ROW_LIMIT, csvOptions);
        if (parsedRows.header().isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, String> header = parsedRows.header();
        List<String> sourceNames = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String name = StringUtils.defaultIfBlank(header.get(i), "column_" + (i + 1));
            sourceNames.add(name);
        }
        requireUniqueSourceColumns(sourceNames);

        List<List<String>> previewData = new ArrayList<>();
        for (Map<Integer, String> row : parsedRows.data()) {
            List<String> values = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < sourceNames.size(); columnIndex++) {
                values.add(StringUtils.defaultString(row.get(columnIndex)));
            }
            previewData.add(values);
        }

        TableMetadataRequest targetRequest = TrustedMetadataRequestResolver.table(dataSourceId, databaseName, schemaName,
                tableName);
        List<ImportTargetColumn> targetColumns = targetColumns(targetRequest);
        List<ImportColumnMapping> suggested = new ArrayList<>();
        if (parsedRows.syntheticHeader()) {
            List<ImportTargetColumn> importableTargets = targetColumns.stream()
                    .filter(target -> !target.isAutoIncrement())
                    .toList();
            for (int index = 0; index < Math.min(sourceNames.size(), importableTargets.size()); index++) {
                suggested.add(ImportColumnMapping.builder()
                        .sourceColumn(sourceNames.get(index))
                        .targetColumn(importableTargets.get(index).getName())
                        .build());
            }
        } else {
            for (String source : sourceNames) {
                targetColumns.stream()
                        .filter(target -> StringUtils.equalsIgnoreCase(target.getName(), source))
                        .findFirst()
                        .map(target -> ImportColumnMapping.builder()
                                .sourceColumn(source)
                                .targetColumn(target.getName())
                                .build())
                        .ifPresent(suggested::add);
            }
        }

        return ImportPreview.builder()
                .sourceColumns(sourceNames)
                .previewData(previewData)
                .targetTableName(targetRequest.getTableName())
                .targetColumns(targetColumns)
                .suggestedMapping(suggested)
                .previewLimit(PREVIEW_ROW_LIMIT)
                .build();
    }

    private static List<ImportTargetColumn> targetColumns(TableMetadataRequest target) {
        Connection connection = Chat2DBContext.getConnection();
        return Chat2DBContext.getDbMetaData().columns(connection,
                        target).stream()
                .map(column -> ImportTargetColumn.builder()
                        .name(column.getName())
                        .dataType(column.getColumnType())
                        .nullable(column.getNullable() != null && column.getNullable() == 1)
                        .autoIncrement(Boolean.TRUE.equals(column.getAutoIncrement()))
                        .defaultValue(column.getDefaultValue())
                        .comment(column.getComment())
                        .build())
                .toList();
    }

    private static void requireUniqueSourceColumns(List<String> sourceColumns) {
        HashSet<String> names = new HashSet<>();
        for (String sourceColumn : sourceColumns) {
            if (!names.add(sourceColumn.toUpperCase(Locale.ROOT))) {
                throw new BusinessException("import.preview.duplicateSourceColumns", new Object[]{sourceColumn});
            }
        }
    }

}
