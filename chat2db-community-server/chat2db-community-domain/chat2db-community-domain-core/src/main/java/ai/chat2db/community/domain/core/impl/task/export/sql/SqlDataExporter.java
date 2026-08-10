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
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
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
import java.util.ArrayList;
import java.util.List;


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
                valueProcessor, tableName, writer, context, progressLogger);
    }

    private void exportSingleInsert(Connection connection, String querySql, Boolean containsHeader,
                                    ISqlBuilder sqlBuilder, IValueProcessor valueProcessor,
                                    String tableName, BufferedWriter writer,
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

    private List<String> extractRowData(ResultSet resultSet, IValueProcessor valueProcessor) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> rowData = new ArrayList<>(metaData.getColumnCount());
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, i, false);
            rowData.add(valueProcessor.getJdbcSqlValueString(jdbcDataValue));
        }
        return rowData;
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
