package ai.chat2db.community.domain.core.impl.task.export.sql;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.api.model.task.ExportAsyncContext;
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
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
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
    protected void singleExport(ExportAsyncContext asyncContext, String tableName, File file) {
        Connection connection = Chat2DBContext.getConnection();
        asyncContext.info(String.format("Exporting data from table %s to %s", tableName, file.getAbsolutePath()));
        try (PrintWriter writer = new PrintWriter(file);) {
            exportSql(connection, asyncContext, tableName, writer);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private void exportSql(Connection connection, ExportAsyncContext asyncContext, String tableName, PrintWriter writer) {
        String databaseName = Chat2DBContext.getConnectInfo().getDatabaseName();
        String schemaName = Chat2DBContext.getConnectInfo().getSchemaName();
        Boolean containsHeader = asyncContext.getContainsHeader();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        String querySql = metaData.getSqlBuilder().dql().buildSelectTable(databaseName, schemaName, tableName);
        ISqlBuilder sqlBuilder = metaData.getSqlBuilder();
        IValueProcessor valueProcessor = metaData.getValueProcessor();
        String sqyType = asyncContext.getSqyType();

        switch (sqyType) {
            case "single" -> exportSingleInsert(connection, querySql, containsHeader, sqlBuilder,
                    valueProcessor, databaseName, schemaName, tableName, writer,asyncContext);
            case "multi" -> exportMultiInsert(connection, querySql, containsHeader, sqlBuilder,
                    valueProcessor, databaseName, schemaName, tableName, writer,asyncContext);
            case "update" -> exportUpdate(connection, querySql, sqlBuilder, valueProcessor,
                    databaseName, schemaName, tableName, writer,asyncContext);
            default -> throw new IllegalArgumentException("Unsupported sqyType: " + sqyType);
        }
    }

    private void exportSingleInsert(Connection connection, String querySql, Boolean containsHeader,
                                    ISqlBuilder sqlBuilder, IValueProcessor valueProcessor,
                                    String databaseName, String schemaName, String tableName, PrintWriter writer,ExportAsyncContext asyncContext) {
        List<String> sqlList = new ArrayList<>(BATCH_SIZE);
        DefaultSQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet -> {
            List<String> header = containsHeader ? ResultSetUtils.getRsHeader(resultSet) : null;
            int n = 0;
            boolean hasNext = resultSet.next();
            while (hasNext) {
                asyncContext.checkCancelled();
                List<String> rowData = extractRowData(resultSet, valueProcessor);
                String sql = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                        .tableName(tableName)
                        .columnList(header)
                        .valueList(rowData)
                        .build());
                sqlList.add(sql + ";");
                n++;
                hasNext = resultSet.next();
                if (sqlList.size() >= BATCH_SIZE || !hasNext) {
                    writeSqlList(writer, sqlList);
                    asyncContext.info(DateUtil.formatTime(new Date()) + ":" + String.format("Exported %d rows", n));
                }
            }
            writeSqlList(writer, sqlList);
        }, asyncContext, asyncContext::checkCancelled);
    }

    private void exportMultiInsert(Connection connection, String querySql, Boolean containsHeader,
                                   ISqlBuilder sqlBuilder, IValueProcessor valueProcessor,
                                   String databaseName, String schemaName, String tableName, PrintWriter writer,ExportAsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet -> {
            List<List<String>> dataList = new ArrayList<>(BATCH_SIZE);
            List<String> header = containsHeader ? ResultSetUtils.getRsHeader(resultSet) : null;
            while (resultSet.next()) {
                asyncContext.checkCancelled();
                dataList.add(extractRowData(resultSet, valueProcessor));
            }
            String sql = sqlBuilder.dml().buildBatchInsert(MultiInsertSqlRequest.builder()
                    .tableName(tableName)
                    .columnList(header)
                    .valueLists(dataList)
                    .build());
            writer.println(sql+";");
            writer.flush();
        }, asyncContext, asyncContext::checkCancelled);
    }

    private void exportUpdate(Connection connection, String querySql, ISqlBuilder sqlBuilder,
                              IValueProcessor valueProcessor,
                              String databaseName, String schemaName, String tableName, PrintWriter writer,ExportAsyncContext asyncContext) {
        List<String> sqlList = new ArrayList<>(BATCH_SIZE);
        DefaultSQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet -> {
            Map<String, String> primaryKeyMap = getPrimaryKeyMap(connection, databaseName, schemaName, tableName);
            int n = 0;
            while (resultSet.next()) {
                asyncContext.checkCancelled();
                Map<String, String> row = extractRowDataAsMap(resultSet, valueProcessor, primaryKeyMap);
                String sql = sqlBuilder.dml().buildUpdate(UpdateSqlRequest.builder()
                        .databaseName(databaseName)
                        .schemaName(schemaName)
                        .tableName(tableName)
                        .row(row)
                        .primaryKeyMap(primaryKeyMap)
                        .build());
                sqlList.add(sql);
                n++;
                if (sqlList.size() >= BATCH_SIZE || resultSet.isLast()) {
                    writeSqlList(writer, sqlList);
                    asyncContext.info(DateUtil.formatTime(new Date()) + ":" + String.format("Exported %d rows", n));

                }
            }
        }, asyncContext, asyncContext::checkCancelled);
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

    private void writeSqlList(PrintWriter writer, List<String> sqlList) {
        if(CollectionUtils.isEmpty(sqlList)){
            return;
        }
        sqlList.forEach(writer::println);
        sqlList.clear();
    }

}
