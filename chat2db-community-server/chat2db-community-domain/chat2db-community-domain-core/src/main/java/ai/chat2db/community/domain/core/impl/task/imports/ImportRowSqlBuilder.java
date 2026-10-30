package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.model.task.ImportDuplicatePolicy;
import ai.chat2db.community.domain.api.model.task.ImportValueFormat;
import ai.chat2db.community.domain.core.impl.task.imports.reader.ImportCell;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CsvImportValueNormalizer;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/** Maps an import row to INSERT SQL using the existing import conversion rules. */
public final class ImportRowSqlBuilder {
    private final ImportTaskSpec spec;
    private final List<TableColumn> columns;
    private final IValueProcessor valueProcessor;
    private final ConnectInfo connectInfo;
    private final ISqlBuilder sqlBuilder;
    private final ImportValueFormat valueOptions;
    private Map<String, Integer> headMap;
    private Map<String, Integer> mappedHeadMap;
    private List<TableColumn> tableColumns;
    private List<String> tableColumnList;
    /** Source column index per target column, aligned with {@link #tableColumns}; -1 when unmapped. */
    private int[] sourceColumnIndexes = new int[0];
    /** Column metadata is immutable during an import, so the value type is built once per column. */
    private final Map<TableColumn, DataType> columnDataTypes = new IdentityHashMap<>();
    /** INSERT ... VALUES (?,?,?) with a fixed column list, built once and reused for every row. */
    private String insertTemplate;

    public ImportRowSqlBuilder(ImportTaskSpec spec, List<TableColumn> columns) {
        this.spec = spec;
        this.columns = columns;
        this.valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        this.connectInfo = Chat2DBContext.getConnectInfo();
        this.sqlBuilder = Chat2DBContext.getSqlBuilder();
        this.valueOptions = valueOptions(spec);
    }

    /** Each importer writes the options of its own format back to the spec before building rows. */
    private static ImportValueFormat valueOptions(ImportTaskSpec spec) {
        if (spec.getExcelOptions() != null) return spec.getExcelOptions().validate();
        if (spec.getJsonOptions() != null) return spec.getJsonOptions().validate();
        return spec.getCsvOptions() == null ? null : spec.getCsvOptions().validate();
    }

    public void acceptHead(Map<Integer, String> headers) {
        this.headMap = invertMap(headers);
        this.mappedHeadMap = mappedHeadMap();
        this.tableColumns = getTableColumns(columns, headMap);
        this.sourceColumnIndexes = new int[tableColumns.size()];
        for (int columnIndex = 0; columnIndex < tableColumns.size(); columnIndex++) {
            Integer sourceIndex = sourceIndex(tableColumns.get(columnIndex).getName());
            this.sourceColumnIndexes[columnIndex] = sourceIndex == null ? -1 : sourceIndex;
        }
    }

    public String build(Map<Integer, String> row, long sourceRowNumber) {
        Map<Integer, ImportCell> cells = new LinkedHashMap<>();
        row.forEach((index, value) -> cells.put(index, new ImportCell(value, true)));
        return buildCells(cells, sourceRowNumber);
    }

    public String buildCells(Map<Integer, ImportCell> row, long sourceRowNumber) {
        return getInsertSql(getValueList(row, sourceRowNumber));
    }

    /**
     * Prepared-statement shape for this import: fixed column list with placeholders. Pair it with
     * {@link #parametersCells(Map, long)} so the driver can rewrite a whole batch into one multi-row
     * INSERT instead of sending one statement per row.
     */
    public String insertTemplate() {
        if (insertTemplate == null) {
            // The base shape always comes from the dialect SQL builder, so identifiers keep the
            // dialect's own quoting (reserved words, mixed case on PostgreSQL); the duplicate policy
            // only adds its prefix/suffix to that quoted statement.
            String base = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                    .databaseName(connectInfo.getDatabaseName())
                    .schemaName(connectInfo.getSchemaName())
                    .tableName(spec.getTarget().getTableName())
                    .columnList(tableColumnList)
                    .valueList(placeholders())
                    .build());
            insertTemplate = switch (ImportResumability.duplicatePolicy(spec)) {
                case INSERT -> base;
                case IGNORE -> ignoreVariant(base, DatabaseTypeEnum.from(connectInfo.getDbType()));
            };
        }
        return insertTemplate;
    }

    private List<String> placeholders() {
        List<String> placeholders = new ArrayList<>(tableColumnList.size());
        for (int index = 0; index < tableColumnList.size(); index++) {
            placeholders.add("?");
        }
        return placeholders;
    }

    private String qualifiedTableName() {
        StringBuilder name = new StringBuilder();
        if (StringUtils.isNotBlank(connectInfo.getDatabaseName())) {
            name.append(connectInfo.getDatabaseName()).append('.');
        }
        if (StringUtils.isNotBlank(connectInfo.getSchemaName())) {
            name.append(connectInfo.getSchemaName()).append('.');
        }
        return name.append(spec.getTarget().getTableName()).toString();
    }

    /**
     * Builds the parameterized INSERT shape that skips rows the target table already contains.
     *
     * <p>The dialect decides the syntax, and an unsupported dialect is rejected instead of silently
     * degrading to a plain INSERT that would fail the task on the first duplicate key.
     *
     * @throws TaskExecutionException when the dialect cannot express "skip existing rows"
     */
    static String ignoreVariant(String insertSql, DatabaseTypeEnum databaseType) {
        if (databaseType != null && databaseType.isMysqlProtocolFamily()) {
            return insertSql.replaceFirst("(?i)^INSERT\\s+", "INSERT IGNORE ");
        }
        if (databaseType == DatabaseTypeEnum.POSTGRESQL) {
            return insertSql + " ON CONFLICT DO NOTHING";
        }
        throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                I18nUtils.getMessage("import.duplicate.policyUnsupported",
                        new Object[]{ImportDuplicatePolicy.IGNORE.name(),
                                databaseType == null ? "" : databaseType.name()}));
    }

    /** Parameter values for {@link #insertTemplate()}, in column order. */
    public List<Object> parameters(Map<Integer, String> row, long sourceRowNumber) {
        Map<Integer, ImportCell> cells = new LinkedHashMap<>();
        row.forEach((index, value) -> cells.put(index, new ImportCell(value, true)));
        return parametersCells(cells, sourceRowNumber);
    }

    /** Parameter values for {@link #insertTemplate()}, in column order. */
    public List<Object> parametersCells(Map<Integer, ImportCell> row, long sourceRowNumber) {
        List<Object> parameters = new ArrayList<>(tableColumns.size());
        for (int columnIndex = 0; columnIndex < tableColumns.size(); columnIndex++) {
            TableColumn column = tableColumns.get(columnIndex);
            int index = sourceColumnIndexes[columnIndex];
            String value = index < 0 ? null : resolvedValue(row.get(index), column, sourceRowNumber);
            parameters.add(value == null ? null : jdbcParameter(value, column, sourceRowNumber));
        }
        return parameters;
    }

    /**
     * Raw cell values in parameter order. Used for the error artifact when a row is rejected before
     * it could be converted, so the file still shows the original content.
     */
    public List<Object> displayValuesCells(Map<Integer, ImportCell> row) {
        List<Object> values = new ArrayList<>(tableColumns.size());
        for (int columnIndex = 0; columnIndex < tableColumns.size(); columnIndex++) {
            int index = sourceColumnIndexes[columnIndex];
            ImportCell cell = index < 0 ? null : row.get(index);
            values.add(cell == null ? null : cell.display());
        }
        return values;
    }

    /** Raw cell values in parameter order for a plain string row. */
    public List<Object> displayValues(Map<Integer, String> row) {
        Map<Integer, ImportCell> cells = new LinkedHashMap<>();
        row.forEach((index, value) -> cells.put(index, new ImportCell(value, true)));
        return displayValuesCells(cells);
    }

    private static Object jdbcParameter(String value, TableColumn column, long sourceRowNumber) {
        try {
            return CsvImportValueNormalizer.jdbcParameter(value, column);
        } catch (RuntimeException e) {
            if (e instanceof BusinessException) {
                // normalize() already reported row, column and value with the right message key.
                throw e;
            }
            String displayValue = value.length() > 80 ? value.substring(0, 80) + "..." : value;
            throw new BusinessException("import.value.invalidValue",
                    new Object[]{sourceRowNumber, column.getName(), displayValue}, e);
        }
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


    private List<String> getValueList(Map<Integer, ImportCell> data, long sourceRowNumber) {
        List<String> values = new ArrayList<>(tableColumns.size());
        for (int columnIndex = 0; columnIndex < tableColumns.size(); columnIndex++) {
            TableColumn column = tableColumns.get(columnIndex);
            int index = sourceColumnIndexes[columnIndex];
            if (index < 0) {
                values.add(null);
                continue;
            }
            String value = resolvedValue(data.get(index), column, sourceRowNumber);
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
        for (ImportColumnMapping mapping : spec.getColumnMappings()) {
            String source = mapping.getSourceColumn();
            String target = mapping.getTargetColumn();
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
        return spec.getUnmappedTarget() == UnmappedTargetStrategy.NULL
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

    /** Applies the format conversion and native-value rules shared by both output shapes. */
    private String resolvedValue(ImportCell cell, TableColumn column, long sourceRowNumber) {
        String value = cell == null ? null : cell.display();
        if (value == null) {
            return null;
        }
        if (!cell.text()) {
            return CsvImportValueNormalizer.nativeValue(cell.value(), column);
        }
        return valueOptions == null ? value
                : CsvImportValueNormalizer.normalize(value, column, valueOptions, sourceRowNumber);
    }

    private SQLDataValue getSQLDataValue(String value, TableColumn column) {
        DataType dataType = columnDataTypes.computeIfAbsent(column, key -> {
            DataType cached = new DataType();
            cached.setDataTypeName(key.getColumnType());
            cached.setScale(key.getDecimalDigits());
            cached.setPrecision(key.getColumnSize());
            return cached;
        });
        SQLDataValue sqlDataValue = new SQLDataValue();
        sqlDataValue.setDataType(dataType);
        sqlDataValue.setValue(value);
        return sqlDataValue;
    }
}
