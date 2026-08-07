package ai.chat2db.community.domain.core.impl.task.export.sql;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.request.MultiInsertSqlRequest;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.ResultSetUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;


@Slf4j
public class SqlDataExporter extends BaseExporter {

    public SqlDataExporter() {
        this.suffix = ExportFileSuffixEnum.SQL.getSuffix();
        this.contentType = "text/sql";
    }


    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName, File file) {
        Connection connection = Chat2DBContext.getConnection();
        ExportProgressLogger progressLogger = new ExportProgressLogger(context, "SQL", tableName);
        progressLogger.queryStarted("Reading table data from " + tableName);
        try (BufferedWriter writer = createWriter(file)) {
            exportSql(connection, spec, context, tableName, writer, progressLogger);
            progressLogger.queryCompleted("Table data read completed");
            progressLogger.fileFinalizing();
        } catch (IOException e) {
            throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                    "Could not write SQL export", e);
        }
    }

    private void exportSql(Connection connection, ExportTaskSpec spec, TaskExecutionContext context,
            String tableName, BufferedWriter writer, ExportProgressLogger progressLogger) {
        String databaseName = spec.getTarget().getDatabaseName();
        String schemaName = spec.getTarget().getSchemaName();
        Boolean containsHeader = spec.getContainsHeader();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        String querySql = metaData.getSqlBuilder().dql().buildSelectTable(databaseName, schemaName, tableName);
        ISqlBuilder sqlBuilder = metaData.getSqlBuilder();
        IValueProcessor valueProcessor = metaData.getValueProcessor();
        exportSingleInsert(connection, querySql, containsHeader, sqlBuilder,
                valueProcessor, databaseName, schemaName, tableName, writer, context, progressLogger);
    }

    private void exportSingleInsert(Connection connection, String querySql, Boolean containsHeader,
                                    ISqlBuilder sqlBuilder, IValueProcessor valueProcessor,
                                    String databaseName, String schemaName, String tableName, BufferedWriter writer,
                                    TaskExecutionContext context, ExportProgressLogger progressLogger) {
        List<String> sqlList = new ArrayList<>(BATCH_SIZE);
        DefaultSQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet -> {
            List<String> header = Boolean.TRUE.equals(containsHeader) ? ResultSetUtils.getRsHeader(resultSet) : null;
            boolean hasNext = resultSet.next();
            while (hasNext) {
                context.checkCancelled();
                List<String> rowData = extractRowData(resultSet, valueProcessor);
                String sql = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                        .tableName(tableName)
                        .columnList(header)
                        .valueList(rowData)
                        .build());
                sqlList.add(sql + ";");
                progressLogger.recordExportedRow();
                hasNext = resultSet.next();
                if (sqlList.size() >= BATCH_SIZE || !hasNext) {
                    writeSqlList(writer, sqlList);
                }
            }
            writeSqlList(writer, sqlList);
        }, context, context::checkCancelled);
    }

    private void exportMultiInsert(Connection connection, String querySql, Boolean containsHeader,
                                   ISqlBuilder sqlBuilder, IValueProcessor valueProcessor,
                                   String databaseName, String schemaName, String tableName, BufferedWriter writer,
                                   TaskExecutionContext context, ExportProgressLogger progressLogger) {
        DefaultSQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet -> {
            List<List<String>> dataList = new ArrayList<>(BATCH_SIZE);
            List<String> header = Boolean.TRUE.equals(containsHeader) ? ResultSetUtils.getRsHeader(resultSet) : null;
            while (resultSet.next()) {
                context.checkCancelled();
                dataList.add(extractRowData(resultSet, valueProcessor));
                progressLogger.recordExportedRow();
            }
            String sql = sqlBuilder.dml().buildBatchInsert(MultiInsertSqlRequest.builder()
                    .tableName(tableName)
                    .columnList(header)
                    .valueLists(dataList)
                    .build());
            writeSqlLine(writer, sql + ";");
            flush(writer);
        }, context, context::checkCancelled);
    }

    private void exportUpdate(Connection connection, String querySql, ISqlBuilder sqlBuilder,
                              IValueProcessor valueProcessor,
                              String databaseName, String schemaName, String tableName, BufferedWriter writer,
                              TaskExecutionContext context, ExportProgressLogger progressLogger) {
        List<String> sqlList = new ArrayList<>(BATCH_SIZE);
        DefaultSQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet -> {
            Map<String, String> primaryKeyMap = getPrimaryKeyMap(connection, databaseName, schemaName, tableName);
            while (resultSet.next()) {
                context.checkCancelled();
                Map<String, String> row = extractRowDataAsMap(resultSet, valueProcessor, primaryKeyMap);
                String sql = sqlBuilder.dml().buildUpdate(UpdateSqlRequest.builder()
                        .databaseName(databaseName)
                        .schemaName(schemaName)
                        .tableName(tableName)
                        .row(row)
                        .primaryKeyMap(primaryKeyMap)
                        .build());
                sqlList.add(sql);
                progressLogger.recordExportedRow();
                if (sqlList.size() >= BATCH_SIZE || resultSet.isLast()) {
                    writeSqlList(writer, sqlList);
                }
            }
        }, context, context::checkCancelled);
    }

    private List<String> extractRowData(ResultSet resultSet, IValueProcessor valueProcessor) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> rowData = new ArrayList<>(metaData.getColumnCount());
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, i, false);
            rowData.add(valueProcessor.getJdbcSqlValueString(jdbcDataValue));
        }
        return rowData;
    }

    private Map<String, String> extractRowDataAsMap(ResultSet resultSet, IValueProcessor valueProcessor,
                                                    Map<String, String> primaryKeyMap) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        Map<String, String> row = new HashMap<>(metaData.getColumnCount());
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, i, false);
            String columnName = metaData.getColumnName(i);
            String jdbcValueString = valueProcessor.getJdbcSqlValueString(jdbcDataValue);
            if (primaryKeyMap.containsKey(columnName)) {
                primaryKeyMap.put(columnName, jdbcValueString);
            } else {
                row.put(columnName, jdbcValueString);
            }
        }
        return row;
    }

    private Map<String, String> getPrimaryKeyMap(Connection connection, String databaseName,
                                                 String schemaName, String tableName) throws SQLException {
        Map<String, String> primaryKeyMap = new HashMap<>();
        try (ResultSet primaryKeys = connection.getMetaData().getPrimaryKeys(databaseName, schemaName, tableName)) {
            while (primaryKeys.next()) {
                primaryKeyMap.put(primaryKeys.getString("COLUMN_NAME"), "");
            }
        }
        return primaryKeyMap;
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

    private void writeSqlLine(BufferedWriter writer, String sql) {
        try {
            writer.write(sql);
            writer.newLine();
        } catch (IOException e) {
            throw fileWriteFailure(e);
        }
    }

    private void flush(BufferedWriter writer) {
        try {
            writer.flush();
        } catch (IOException e) {
            throw fileWriteFailure(e);
        }
    }

    private TaskExecutionException fileWriteFailure(IOException cause) {
        return new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                "Could not write SQL export", cause);
    }

}
