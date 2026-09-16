package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
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
    private final CsvOptions csvOptions;
    private Map<String, Integer> headMap;
    private Map<String, Integer> mappedHeadMap;
    private List<TableColumn> tableColumns;
    private List<String> tableColumnList;

    public ImportRowSqlBuilder(ImportTaskSpec spec, List<TableColumn> columns) {
        this.spec = spec;
        this.columns = columns;
        this.valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        this.connectInfo = Chat2DBContext.getConnectInfo();
        this.sqlBuilder = Chat2DBContext.getSqlBuilder();
        this.csvOptions = spec.getCsvOptions() == null ? null : spec.getCsvOptions().validate();
    }

    public void acceptHead(Map<Integer, String> headers) {
        this.headMap = invertMap(headers);
        this.mappedHeadMap = mappedHeadMap();
        this.tableColumns = getTableColumns(columns, headMap);
    }

    public String build(Map<Integer, String> row, long sourceRowNumber) {
        return getInsertSql(getValueList(row, sourceRowNumber));
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


    private List<String> getValueList(Map<Integer, String> data, long sourceRowNumber) {
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
                if (csvOptions != null) {
                    value = CsvImportValueNormalizer.normalize(value, column, csvOptions, sourceRowNumber);
                }
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

    private SQLDataValue getSQLDataValue(String value, TableColumn column) {
        DataType dataType = new DataType();
        dataType.setDataTypeName(column.getColumnType());
        dataType.setScale(column.getDecimalDigits());
        dataType.setPrecision(column.getColumnSize());
        SQLDataValue sqlDataValue = new SQLDataValue();
        sqlDataValue.setDataType(dataType);
        sqlDataValue.setValue(value);
        return sqlDataValue;
    }
}
