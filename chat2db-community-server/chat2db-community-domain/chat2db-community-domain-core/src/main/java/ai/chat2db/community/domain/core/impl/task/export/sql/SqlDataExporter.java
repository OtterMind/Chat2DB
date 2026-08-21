package ai.chat2db.community.domain.core.impl.task.export.sql;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.extension.ExportCell;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.ResultSetUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Slf4j
@Component
public class SqlDataExporter extends BaseExporter {

    public SqlDataExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
        this.suffix = ExportFileSuffixEnum.SQL.getSuffix();
        this.contentType = "text/sql";
    }

    @Override
    public String type() {
        return "sql";
    }


    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName, File file) {
        Connection connection = Chat2DBContext.getConnection();
        SqlExecutionPlan executionPlan = getQueryPlan(spec, tableName);
        ExportProgressLogger progressLogger = new ExportProgressLogger(context, "SQL", tableName);
        progressLogger.queryStarted("Reading table data from " + tableName);
        try (BufferedWriter writer = createWriter(file)) {
            exportSql(connection, spec, executionPlan, context, tableName, writer, progressLogger);
            progressLogger.queryCompleted("Table data read completed");
            progressLogger.fileFinalizing();
        } catch (IOException e) {
            throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                    "Could not write SQL export", e);
        }
    }

    private void exportSql(Connection connection, ExportTaskSpec spec, SqlExecutionPlan executionPlan,
            TaskExecutionContext context, String tableName, BufferedWriter writer,
            ExportProgressLogger progressLogger) {
        Boolean containsHeader = spec.getContainsHeader();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        ISqlBuilder sqlBuilder = metaData.getSqlBuilder();
        IValueProcessor valueProcessor = metaData.getValueProcessor();
        exportSingleInsert(connection, spec, executionPlan, containsHeader, sqlBuilder,
                valueProcessor, tableName, writer, context, progressLogger);
    }

    private void exportSingleInsert(Connection connection, ExportTaskSpec spec, SqlExecutionPlan executionPlan,
            Boolean containsHeader, ISqlBuilder sqlBuilder, IValueProcessor valueProcessor,
            String tableName, BufferedWriter writer, TaskExecutionContext context,
            ExportProgressLogger progressLogger) {
        List<String> sqlList = new ArrayList<>(BATCH_SIZE);
        DefaultSQLExecutor.getInstance().execute(connection, executionPlan.getSql(), BATCH_SIZE, resultSet -> {
            ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
            List<Integer> includedColumnIndexes = includedColumnIndexes(resultSetMetaData, executionPlan);
            if (includedColumnIndexes.isEmpty()) {
                throw new IllegalStateException("SQL export has no authorized columns");
            }
            List<String> header = !Boolean.TRUE.equals(containsHeader)
                    && includedColumnIndexes.size() == resultSetMetaData.getColumnCount()
                    ? null : selectColumns(ResultSetUtils.getRsHeader(resultSet), includedColumnIndexes);
            int exportedRows = 0;
            boolean hasNext = nextRow(resultSet, executionPlan, exportedRows);
            while (hasNext) {
                context.checkCancelled();
                List<String> rowData = extractRowData(resultSet, spec, valueProcessor, tableName,
                        includedColumnIndexes);
                String sql = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                        .tableName(tableName)
                        .columnList(header)
                        .valueList(rowData)
                        .build());
                sqlList.add(sql + ";");
                progressLogger.recordExportedRow();
                exportedRows++;
                hasNext = nextRow(resultSet, executionPlan, exportedRows);
                if (sqlList.size() >= BATCH_SIZE || !hasNext) {
                    writeSqlList(writer, sqlList);
                }
            }
            writeSqlList(writer, sqlList);
        }, context, context::checkCancelled);
    }

    private List<String> extractRowData(ResultSet resultSet, ExportTaskSpec spec, IValueProcessor valueProcessor,
            String tableName, List<Integer> includedColumnIndexes) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> rowData = new ArrayList<>(includedColumnIndexes.size());
        for (Integer columnIndex : includedColumnIndexes) {
            JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, columnIndex, false);
            if (!hasExportCellProcessors()) {
                rowData.add(valueProcessor.getJdbcSqlValueString(jdbcDataValue));
                continue;
            }
            ExportCell processedCell = processJdbcCell(spec, metaData, columnIndex, tableName, jdbcDataValue);
            DataType dataType = new DataType();
            dataType.setDataTypeName(processedCell.getTypeName());
            dataType.setPrecision(processedCell.getPrecision());
            dataType.setScale(processedCell.getScale());
            SQLDataValue sqlDataValue = new SQLDataValue();
            sqlDataValue.setDataType(dataType);
            sqlDataValue.setValue(toSqlValue(processedCell.getValue()));
            rowData.add(valueProcessor.getSqlValueString(sqlDataValue));
        }
        return rowData;
    }

    private String toSqlValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "0x" + HexFormat.of().withUpperCase().formatHex(bytes);
        }
        if (value instanceof char[] chars) {
            return new String(chars);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<String> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(toSqlValue(Array.get(value, index)));
            }
            return values.toString();
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(this::toSqlValue).toList().toString();
        }
        if (value instanceof Map<?, ?> values) {
            Map<String, String> serialized = new LinkedHashMap<>(values.size());
            values.forEach((key, mapValue) -> serialized.put(toSqlValue(key), toSqlValue(mapValue)));
            return serialized.toString();
        }
        return String.valueOf(value);
    }

    BufferedWriter createWriter(File file) throws IOException {
        return Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
    }

    void writeSqlList(BufferedWriter writer, List<String> sqlList) {
        if (CollectionUtils.isEmpty(sqlList)) {
            return;
        }
        try {
            for (String sql : sqlList) {
                writer.write(sql);
                writer.newLine();
            }
            sqlList.clear();
        } catch (IOException e) {
            throw fileWriteFailure(e);
        }
    }

    private TaskExecutionException fileWriteFailure(IOException cause) {
        return new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                "Could not write SQL export", cause);
    }

}
