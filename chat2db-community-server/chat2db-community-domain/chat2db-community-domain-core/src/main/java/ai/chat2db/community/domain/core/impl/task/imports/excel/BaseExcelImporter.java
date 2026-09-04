package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.core.impl.db.ExcelImportConfig;
import ai.chat2db.community.domain.core.impl.db.ExcelParser;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportSqlExecutor;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.util.ConverterUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.*;


@Slf4j
public abstract class BaseExcelImporter extends BaseImporter {
    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        context.checkCancelled();
        ExcelTypeEnum excelType = getExcelType();
        Map<String, Object> options = spec.getImportOptions() == null ? Map.of() : spec.getImportOptions();
        if (excelType != ExcelTypeEnum.CSV) {
            doImportExcelData(spec, context, columns, ExcelImportConfig.from(options));
            return;
        }
        ImportOptions importOptions = importOptions(options);
        NoModelDataListener noModelDataListener = new NoModelDataListener(spec, context, columns,
                importOptions.firstDataRowIndex());
        EasyExcel.read(new File(spec.getSourceFile()), noModelDataListener)
                .excelType(excelType)
                .sheet(importOptions.sheetName())
                .headRowNumber(importOptions.rowsBeforeData())
                .doRead();
        context.checkCancelled();

    }

    protected abstract ExcelTypeEnum getExcelType();

    private void doImportExcelData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns,
            ExcelImportConfig config) {
        File file = new File(spec.getSourceFile());
        ExcelDataImporter importer = new ExcelDataImporter(spec, context, columns, config);
        importer.initializeHeader(file);
        importer.addSkippedRows(ExcelParser.parseRows(file, file.getName(), config, importer::invoke));
        importer.doAfterAllAnalysed();
    }

    private static int numberOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        return value instanceof Number number ? Math.max(0, number.intValue()) : defaultValue;
    }

    private static ImportOptions importOptions(Map<String, Object> options) {
        String requestedSheetName = (String) options.get("sheetName");
        int startRow = numberOption(options, "startRow", 0);
        int headerRow = numberOption(options, "headerRow", 1);
        String sheetName = StringUtils.isBlank(requestedSheetName) ? null : requestedSheetName;
        int rowsBeforeData = headerRow > 0 ? headerRow : 0;
        int firstDataRowIndex = headerRow > 0 ? Math.max(headerRow, startRow) : startRow;
        return new ImportOptions(sheetName, rowsBeforeData, firstDataRowIndex);
    }

    private record ImportOptions(String sheetName, int rowsBeforeData, int firstDataRowIndex) {
    }

    private class ExcelDataImporter {

        private static final int BATCH_SIZE = 1000;

        private final ImportTaskSpec spec;
        private final TaskExecutionContext taskContext;
        private final List<TableColumn> columns;
        private final ExcelImportConfig config;
        private final IValueProcessor valueProcessor;
        private final ConnectInfo connectInfo;
        private final ISqlBuilder sqlBuilder;
        private final ImportSqlExecutor sqlExecutor;
        private Map<String, Integer> headMap;
        private Map<String, Integer> mappedHeadMap;
        private List<TableColumn> tableColumns;
        private List<String> tableColumnList;
        private List<RowSql> sqlList;
        private long successCount;
        private long skippedCount;
        private long processedCount;
        private final long estimatedTotalRows;

        private ExcelDataImporter(ImportTaskSpec spec, TaskExecutionContext taskContext,
                List<TableColumn> columns, ExcelImportConfig config) {
            this.spec = spec;
            this.taskContext = taskContext;
            this.columns = columns;
            this.config = config;
            this.valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            this.connectInfo = Chat2DBContext.getConnectInfo();
            this.sqlBuilder = Chat2DBContext.getSqlBuilder();
            this.sqlExecutor = new ImportSqlExecutor(taskContext);
            this.estimatedTotalRows = estimateRows(config);
        }

        private void addSkippedRows(long skippedRows) {
            this.skippedCount += skippedRows;
        }

        private void initializeHeader(File file) {
            taskContext.checkCancelled();
            if (config.headerRow() <= 0) {
                return;
            }
            ExcelParser.ExcelResult headerResult = ExcelParser.parse(file, file.getName(), config, 0);
            if (headerResult.rows().isEmpty()) {
                throw new BusinessException("import.excel.headerMissing");
            }
            initializeHeader(headerResult.rows().get(0));
        }

        private void initializeHeader(Map<Integer, ExcelParser.CellValue> header) {
            this.headMap = invertMap(header);
            this.mappedHeadMap = mappedHeadMap();
            this.tableColumns = getTableColumns(columns);
        }

        private List<TableColumn> getTableColumns(List<TableColumn> columns) {
            List<TableColumn> resolvedColumns = new ArrayList<>();
            this.tableColumnList = new ArrayList<>();
            for (TableColumn column : columns) {
                if (shouldInclude(column)) {
                    resolvedColumns.add(column);
                    this.tableColumnList.add(column.getName());
                }
            }
            if (resolvedColumns.isEmpty()) {
                throw new BusinessException("import.preview.noColumns");
            }
            return resolvedColumns;
        }

        private Map<String, Integer> invertMap(Map<Integer, ExcelParser.CellValue> map) {
            Map<String, Integer> out = new HashMap<>(map.size());
            for (Map.Entry<Integer, ExcelParser.CellValue> entry : map.entrySet()) {
                ExcelParser.CellValue cellValue = entry.getValue();
                String name = cellValue == null ? null : cellValue.value();
                if (StringUtils.isBlank(name)) {
                    name = "column_" + (entry.getKey() + 1);
                }
                String normalized = name.toUpperCase(Locale.ROOT);
                if (out.putIfAbsent(normalized, entry.getKey()) != null) {
                    throw new BusinessException("import.excel.duplicateHeader", new Object[] {name});
                }
            }
            return out;
        }

        private void invoke(Integer rowIndex, Map<Integer, ExcelParser.CellValue> data) {
            this.taskContext.checkCancelled();
            if (headMap == null) {
                initializeSyntheticHeaders(data);
            }
            List<String> values = getValueList(data);
            String sql = getInsertSql(values);
            if (StringUtils.isBlank(sql)) {
                skippedCount++;
                return;
            }
            if (sqlList == null) {
                sqlList = new ArrayList<>();
            }
            sqlList.add(new RowSql(rowIndex + 1, sql));
            processedCount++;
            if (sqlList.size() >= BATCH_SIZE) {
                executeBatchInsert();
            }
        }

        private void initializeSyntheticHeaders(Map<Integer, ExcelParser.CellValue> data) {
            this.headMap = new HashMap<>();
            if (spec.getColumnMappings() != null && !spec.getColumnMappings().isEmpty()) {
                for (Map<String, String> mapping : spec.getColumnMappings()) {
                    Integer index = syntheticColumnIndex(mapping.get("sourceColumn"));
                    if (index != null) {
                        this.headMap.put("COLUMN_" + (index + 1), index);
                    }
                }
            } else {
                for (Integer index : data.keySet()) {
                    this.headMap.put("COLUMN_" + (index + 1), index);
                }
            }
            this.mappedHeadMap = mappedHeadMap();
            this.tableColumns = getTableColumns(columns);
        }

        private Integer syntheticColumnIndex(String sourceColumn) {
            if (StringUtils.isBlank(sourceColumn)) {
                return null;
            }
            String normalized = sourceColumn.trim().toUpperCase(Locale.ROOT);
            if (!normalized.startsWith("COLUMN_")) {
                return null;
            }
            try {
                int column = Integer.parseInt(normalized.substring("COLUMN_".length()));
                return column <= 0 ? null : column - 1;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private List<String> getValueList(Map<Integer, ExcelParser.CellValue> data) {
            List<String> values = new ArrayList<>();
            for (TableColumn column : tableColumns) {
                Integer index = sourceIndex(column.getName());
                if (index == null) {
                    values.add(null);
                    continue;
                }
                ExcelParser.CellValue cellValue = data.get(index);
                String value = cellValue == null ? (config.emptyAsNull() ? null : "") : cellValue.value();
                if (value == null) {
                    values.add(null);
                } else {
                    values.add(valueProcessor.getSqlValueString(getSQLDataValue(value, column)));
                }
            }
            return values;
        }

        private Map<String, Integer> mappedHeadMap() {
            Map<String, Integer> mapped = new HashMap<>();
            if (spec.getColumnMappings() == null) {
                return mapped;
            }
            for (Map<String, String> mapping : spec.getColumnMappings()) {
                String source = mapping.get("sourceColumn");
                String target = mapping.get("targetColumn");
                Integer sourceIndex = headMap.get(source == null ? null : source.toUpperCase(Locale.ROOT));
                if (sourceIndex != null && StringUtils.isNotBlank(target)) {
                    String normalizedTarget = target.toUpperCase(Locale.ROOT);
                    if (mapped.putIfAbsent(normalizedTarget, sourceIndex) != null) {
                        throw new BusinessException("import.excel.duplicateTargetMapping",
                                new Object[] {target});
                    }
                }
            }
            return mapped;
        }

        private Integer sourceIndex(String targetColumn) {
            String target = targetColumn.toUpperCase(Locale.ROOT);
            if (spec.getColumnMappings() != null) {
                return mappedHeadMap.get(target);
            }
            return headMap.get(target);
        }

        private boolean shouldInclude(TableColumn column) {
            if (spec.getColumnMappings() == null) {
                return sourceIndex(column.getName()) != null;
            }
            if (sourceIndex(column.getName()) != null) {
                return true;
            }
            return "NULL".equalsIgnoreCase(spec.getUnmappedTarget())
                    && !Boolean.TRUE.equals(column.getAutoIncrement());
        }

        private String getInsertSql(List<String> values) {
            return sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                    .databaseName(connectInfo.getDatabaseName())
                    .schemaName(connectInfo.getSchemaName())
                    .tableName(spec.getTarget().getTableName())
                    .columnList(this.tableColumnList)
                    .valueList(values)
                    .build());
        }

        private void doAfterAllAnalysed() {
            this.taskContext.checkCancelled();
            executeBatchInsert();
            taskContext.reportProgress(90, TaskStage.IMPORTING.name(),
                    "Imported rows: " + successCount);
            taskContext.logInfo("IMPORT_SUMMARY", "Data import completed", Map.of(
                    "successCount", successCount,
                    "failedCount", 0L,
                    "skippedCount", skippedCount));
        }

        private void executeBatchInsert() {
            taskContext.checkCancelled();
            if (sqlList != null && !sqlList.isEmpty()) {
                sqlExecutor.executeBatch(sqlList.stream().map(RowSql::sql).toList());
                successCount += sqlList.size();
                taskContext.reportProgress(progress(), TaskStage.IMPORTING.name(),
                        "Imported rows: " + successCount);
            }
            sqlList = new ArrayList<>();
        }

        private int progress() {
            long imported = Math.min(estimatedTotalRows, Math.max(0L, processedCount));
            return Math.min(90, 20 + (int) ((imported * 70L) / estimatedTotalRows));
        }

        private long estimateRows(ExcelImportConfig config) {
            if (config.endRow() <= 0) {
                return 1L;
            }
            return Math.max(1L, config.endRowIndex() - config.firstDataRowIndex() + 1L);
        }

        private record RowSql(int number, String sql) {
        }
    }


    public class NoModelDataListener extends AnalysisEventListener<Map<Integer, String>> {


        private final ImportTaskSpec spec;

        private final TaskExecutionContext taskContext;

        private final List<TableColumn> columns;

        private Map<String, Integer> headMap;

        private Map<String, Integer> mappedHeadMap;

        private List<TableColumn> tableColumns;

        private List<String> tableColumnList;

        private List<RowSql> sqlList;

        private long successCount;

        private long failedCount;

        private long skippedCount;

        private static final int BATCH_SIZE = 1000;

        private final IValueProcessor valueProcessor;

        private final ConnectInfo connectInfo;

        private final ISqlBuilder sqlBuilder;

        private final ImportSqlExecutor sqlExecutor;

        private final int firstDataRowIndex;

        public NoModelDataListener(ImportTaskSpec spec, TaskExecutionContext taskContext,
                List<TableColumn> columns, int firstDataRowIndex) {
            this.spec = spec;
            this.columns = columns;
            this.taskContext = taskContext;
            this.valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            this.connectInfo = Chat2DBContext.getConnectInfo();
            this.sqlBuilder = Chat2DBContext.getSqlBuilder();
            this.sqlExecutor = new ImportSqlExecutor(taskContext);
            this.firstDataRowIndex = firstDataRowIndex;
        }


        @Override
        public void invokeHead(Map<Integer, ReadCellData<?>> headMap, AnalysisContext context) {
            this.taskContext.checkCancelled();
            Map<Integer, String> map = ConverterUtils.convertToStringMap(headMap, context);
            this.headMap = invertMap(map);
            this.mappedHeadMap = mappedHeadMap();
            this.tableColumns = getTableColumns(columns, this.headMap);
        }

        private List<TableColumn> getTableColumns(List<TableColumn> columns, Map<String, Integer> headMap) {
            List<TableColumn> tableColumns = new ArrayList<>();
            this.tableColumnList = new ArrayList<>();
            for (TableColumn column : columns) {
                if (shouldInclude(column)) {
                    tableColumns.add(column);
                    this.tableColumnList.add(column.getName());
                }
            }
            return tableColumns;
        }

        private Map<String, Integer> invertMap(Map<Integer, String> map) {
            Map<String, Integer> out = new HashMap(map.size());
            Iterator it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, String> entry = (Map.Entry) it.next();
                if (entry.getValue() != null) {
                    out.put(entry.getValue().toUpperCase(Locale.ROOT), entry.getKey());
                }
            }
            return out;
        }


        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            this.taskContext.checkCancelled();
            if (context.readRowHolder().getRowIndex() < firstDataRowIndex) {
                return;
            }
            if (data == null || data.isEmpty()) {
                skippedCount++;
                return;
            }
            if (headMap == null) {
                initializeSyntheticHeaders(data);
            }
            List<String> values = getValueList(data);

            String sql = getInsertSql(values);

            if (StringUtils.isBlank(sql)) {
                skippedCount++;
                return;
            }
            if (sqlList == null) {
                sqlList = new ArrayList<>();
            }
            sqlList.add(new RowSql(context.readRowHolder().getRowIndex() + 1, sql));
            if (sqlList.size() >= BATCH_SIZE) {
                executeBatchInsert();
            } else {

            }
        }

        private void initializeSyntheticHeaders(Map<Integer, String> data) {
            this.headMap = new HashMap<>();
            for (Integer index : data.keySet()) {
                this.headMap.put("COLUMN_" + (index + 1), index);
            }
            this.mappedHeadMap = mappedHeadMap();
            this.tableColumns = getTableColumns(columns, this.headMap);
        }

        private List<String> getValueList(Map<Integer, String> data) {
            List<String> values = new ArrayList<>();
            for (TableColumn column : tableColumns) {
                Integer index = sourceIndex(column.getName());
                if (index == null) {
                    values.add(null);
                    continue;
                }
                String value = data.get(index);
                if (value == null) {
                    values.add(null);
                } else {
                    String stringValue = valueProcessor.getSqlValueString(getSQLDataValue(value, column));
                    values.add(stringValue);
                }
            }
            return values;
        }

        private Map<String, Integer> mappedHeadMap() {
            Map<String, Integer> mapped = new HashMap<>();
            if (spec.getColumnMappings() == null) {
                return mapped;
            }
            for (Map<String, String> mapping : spec.getColumnMappings()) {
                String source = mapping.get("sourceColumn");
                String target = mapping.get("targetColumn");
                Integer sourceIndex = headMap.get(source == null ? null : source.toUpperCase(Locale.ROOT));
                if (sourceIndex != null && StringUtils.isNotBlank(target)) {
                    mapped.put(target.toUpperCase(Locale.ROOT), sourceIndex);
                }
            }
            return mapped;
        }

        private Integer sourceIndex(String targetColumn) {
            String target = targetColumn.toUpperCase(Locale.ROOT);
            if (spec.getColumnMappings() != null) {
                return mappedHeadMap.get(target);
            }
            return headMap.get(target);
        }

        private boolean shouldInclude(TableColumn column) {
            if (spec.getColumnMappings() == null) {
                return sourceIndex(column.getName()) != null;
            }
            if (sourceIndex(column.getName()) != null) {
                return true;
            }
            return "NULL".equalsIgnoreCase(spec.getUnmappedTarget())
                    && !Boolean.TRUE.equals(column.getAutoIncrement());
        }

        private String getInsertSql(List<String> values) {
            return sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                    .databaseName(connectInfo.getDatabaseName())
                    .schemaName(connectInfo.getSchemaName())
                    .tableName(spec.getTarget().getTableName())
                    .columnList(this.tableColumnList)
                    .valueList(values)
                    .build());
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            this.taskContext.checkCancelled();
            executeBatchInsert();
            taskContext.logInfo("IMPORT_SUMMARY", "Data import completed", Map.of(
                    "successCount", successCount,
                    "failedCount", failedCount,
                    "skippedCount", skippedCount));
        }

        private void executeBatchInsert() {
            taskContext.checkCancelled();
            if (sqlList != null && !sqlList.isEmpty()) {
                sqlExecutor.executeBatch(sqlList.stream().map(RowSql::sql).toList());
                successCount += sqlList.size();
            }
            sqlList = new ArrayList<>();
        }

        private record RowSql(int number, String sql) {
        }
    }

}
