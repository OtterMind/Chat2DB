package ai.chat2db.spi;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.request.*;
import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import com.jcraft.jsch.Session;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.ConnectionException;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.JdbcDriverManager;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.ssh.SSHManager;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.ResultSetUtils;
import cn.hutool.core.date.DateUtil;
import static cn.hutool.core.date.DatePattern.NORM_DATETIME_PATTERN;


@Slf4j
public class DefaultDBManager implements IDbManager {

    private static final int DEFAULT_EXPORT_BATCH_SIZE = 100000;
    private static final int FIRST_PAGE = 1;
    private static final int FIRST_OFFSET = 0;

    /**
     * Bounds of one multi-value {@code INSERT} emitted by the keyset export path.
     */
    private static final int SQL_STATEMENT_MAX_ROWS = 800;
    private static final long SQL_STATEMENT_MAX_BYTES = 1024L * 1024L;

    private static final String SQL_COPY_TABLE_DATA = "CREATE TABLE %s AS SELECT * FROM %s";
    private static final String SQL_COPY_TABLE_STRUCTURE = "CREATE TABLE %s AS SELECT * FROM %s WHERE 1=0";
    private static final String SQL_SET_FOREIGN_KEY_CHECKS_DISABLED = "SET FOREIGN_KEY_CHECKS=0;";
    private static final String SQL_SET_FOREIGN_KEY_CHECKS_ENABLED = "SET FOREIGN_KEY_CHECKS=1;";
    private static final String SQL_TRUNCATE_TABLE = "TRUNCATE TABLE %s";

    protected static final String DIVIDING_LINE = "-- ----------------------------";


    protected static final String EXPORT_TITLE = DIVIDING_LINE + SQLConstants.LINE_SEPARATOR + "-- Chat2DB export data , export time: %s" + SQLConstants.LINE_SEPARATOR + DIVIDING_LINE;

    protected static final String TABLE_TITLE = DIVIDING_LINE + SQLConstants.LINE_SEPARATOR + "-- Table structure for table %s" + SQLConstants.LINE_SEPARATOR + DIVIDING_LINE;

    protected static final String VIEW_TITLE = DIVIDING_LINE + SQLConstants.LINE_SEPARATOR + "-- View structure for view %s" + SQLConstants.LINE_SEPARATOR + DIVIDING_LINE;

    protected static final String FUNCTION_TITLE = DIVIDING_LINE + SQLConstants.LINE_SEPARATOR + "-- Function structure for function %s" + SQLConstants.LINE_SEPARATOR + DIVIDING_LINE;

    protected static final String TRIGGER_TITLE = DIVIDING_LINE + SQLConstants.LINE_SEPARATOR + "-- Trigger structure for trigger %s" + SQLConstants.LINE_SEPARATOR + DIVIDING_LINE;

    protected static final String PROCEDURE_TITLE = DIVIDING_LINE + SQLConstants.LINE_SEPARATOR + "-- Procedure structure for procedure %s" + SQLConstants.LINE_SEPARATOR + DIVIDING_LINE;

    protected static final String RECORD_TITLE = DIVIDING_LINE + SQLConstants.LINE_SEPARATOR + "-- Records of %s" + SQLConstants.LINE_SEPARATOR + DIVIDING_LINE;

    protected static final String EXPORT_TASK_STAGE = TaskStage.EXPORTING.name();

    protected static void reportExportProgress(TaskExecutionContext context, int progress) {
        context.reportProgress(progress, EXPORT_TASK_STAGE, "Exporting database objects");
    }

    protected static void logDatabaseObjectExportStarted(TaskExecutionContext context, String objectType) {
        context.logInfo(TaskEventCode.DATABASE_OBJECT_EXPORT_STARTED.name(),
                "Exporting database " + objectType,
                Map.of(TaskConstants.OBJECT_TYPE_DETAIL_KEY, objectType));
    }

    protected static void logDatabaseObjectExportCompleted(TaskExecutionContext context, String objectType) {
        context.logInfo(TaskEventCode.DATABASE_OBJECT_EXPORT_COMPLETED.name(),
                "Database " + objectType + " exported",
                Map.of(TaskConstants.OBJECT_TYPE_DETAIL_KEY, objectType));
    }

    private static void logTableQueryStarted(TaskExecutionContext context, String tableName) {
        context.logInfo(TaskEventCode.QUERY_STARTED.name(),
                "Reading table data from " + tableName,
                Map.of(TaskConstants.TABLE_NAME_DETAIL_KEY, tableName));
    }

    private static void logRowsExported(TaskExecutionContext context, String tableName, long exportedRows) {
        context.logInfo(TaskEventCode.ROWS_EXPORTED.name(),
                "Exported " + exportedRows + " rows from " + tableName,
                Map.of(TaskConstants.TABLE_NAME_DETAIL_KEY, tableName,
                        TaskConstants.EXPORTED_ROWS_DETAIL_KEY, exportedRows));
    }

    private static void logTableQueryCompleted(TaskExecutionContext context, String tableName, long exportedRows) {
        context.logInfo(TaskEventCode.QUERY_COMPLETED.name(),
                "Table data read completed: " + exportedRows + " rows from " + tableName,
                Map.of(TaskConstants.TABLE_NAME_DETAIL_KEY, tableName,
                        TaskConstants.EXPORTED_ROWS_DETAIL_KEY, exportedRows));
    }


    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        Connection connection = connectInfo.getConnection();
        SSHInfo ssh = connectInfo.getSsh();
        String url = connectInfo.getUrl();
        String host = connectInfo.getHost();
        String port = connectInfo.getPort() + "";
        Session session = null;
        try {
            if (connection != null && !connection.isClosed()) {
                return connection;
            }
            if (ssh != null && ssh.isUse()) {
                ssh.setRHost(host);
                ssh.setRPort(port);
                session = getSession(ssh);
                if (session != null) {
                    url = JdbcUtils.replaceUrlHostAndPortForSsh(url, host, port, ssh.getLocalPort());
                }
            }
        } catch (Exception e) {
            throw new ConnectionException("connection.ssh.error", null, e);
        }
        try {
            DriverConfig driverConfig = connectInfo.getDriverConfig();
            if (driverConfig == null) {
                driverConfig = Chat2DBContext.getDefaultDriverConfig(connectInfo.getDbType());
            }
            connection = JdbcDriverManager.getConnection(url, connectInfo.getUser(), connectInfo.getPassword(),
                    driverConfig, connectInfo.getExtendMap());

        } catch (Exception e1) {
            close(connection, session, ssh);
            throw new BusinessException("connection.error", null, e1);
        }
        connectInfo.setSession(session);
        connectInfo.setConnection(connection);
        if (StringUtils.isNotBlank(connectInfo.getDatabaseName()) || StringUtils.isNotBlank(connectInfo.getSchemaName())) {
            connectDatabase(connection, connectInfo.getDatabaseName());
            if (StringUtils.isNotBlank(connectInfo.getDatabaseName())) {
                try {
                    connection.setCatalog(connectInfo.getDatabaseName());
                } catch (SQLException e) {
                    log.warn("Failed to set catalog to '{}': {}", connectInfo.getDatabaseName(), e.getMessage());
                }
            }
            if (StringUtils.isNotBlank(connectInfo.getSchemaName())) {
                try {
                    connection.setSchema(connectInfo.getSchemaName());
                } catch (SQLException e) {
                    log.warn("Failed to set schema to '{}': {}", connectInfo.getSchemaName(), e.getMessage());
                }
            }
        }
        return connection;
    }

    private void close(Connection connection, Session session, SSHInfo ssh) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
            }
        }
        if (session != null) {
            try {
                session.delPortForwardingL(Integer.parseInt(ssh.getLocalPort()));
            } catch (Exception e) {
            }
            try {
                session.disconnect();
            } catch (Exception e) {
            }
        }
    }

    private Session getSession(SSHInfo ssh) {
        Session session = null;
        if (ssh != null && ssh.isUse()) {
            session = SSHManager.getSSHSession(ssh);
        }
        return session;
    }

    @Override
    public void connectDatabase(Connection connection, String database) {

    }

    @Override
    public void modifyDatabase(Connection connection, String databaseName, String newDatabaseName) {

    }

    @Override
    public void createDatabase(Connection connection, String databaseName) {

    }

    @Override
    public void dropDatabase(Connection connection, String databaseName) {

    }

    @Override
    public void createSchema(Connection connection, String databaseName, String schemaName) {

    }

    @Override
    public void dropSchema(Connection connection, String databaseName, String schemaName) {

    }

    @Override
    public void modifySchema(Connection connection, String databaseName, String schemaName, String newSchemaName) {

    }

    @Override
    public void dropFunction(Connection connection, String databaseName, String schemaName, String functionName) {

    }

    @Override
    public void dropTrigger(Connection connection, String databaseName, String schemaName, String triggerName) {

    }

    @Override
    public void dropProcedure(Connection connection, String databaseName, String schemaName, String procedureName) {

    }

    @Override
    public void updateProcedure(Connection connection, String databaseName, String schemaName, Procedure procedure)
            throws SQLException {

    }

    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        context.write(String.format(EXPORT_TITLE, DateUtil.format(new Date(), NORM_DATETIME_PATTERN)));
        logDatabaseObjectExportStarted(context, "tables");
        exportTables(connection, databaseName, schemaName, containData, context);
        logDatabaseObjectExportCompleted(context, "tables");
        context.reportProgress(50, EXPORT_TASK_STAGE, "Exporting tables");
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        DatabaseTypeEnum databaseType = DatabaseTypeEnum.from(Chat2DBContext.getConnectInfo().getDbType());
        boolean foreignKeyChecks = databaseType != null && databaseType.isMysqlProtocolFamily();
        if (foreignKeyChecks) {
            context.write(SQL_SET_FOREIGN_KEY_CHECKS_DISABLED);
        }
        List<Table> tables = Chat2DBContext.getDbMetaData().tables(connection, new TablesRequest(databaseName, schemaName, null));
        for (Table table : tables) {
            exportTable(connection, databaseName, schemaName, table.getName(), containData, context);
        }
        if (foreignKeyChecks) {
            context.write(SQL_SET_FOREIGN_KEY_CHECKS_ENABLED);
        }
    }

    @Override
    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName,
            boolean containData, TaskExecutionContext context) throws SQLException {
        String ddl = Chat2DBContext.getDbMetaData().tableDDL(connection, new TableMetadataRequest(databaseName, schemaName, tableName));
        StringBuilder sqlBuilder = new StringBuilder();
        context.write(String.format(TABLE_TITLE, tableName));
        sqlBuilder.append(SQLConstants.DROP_TABLE_IF_EXISTS_SQL_PREFIX).append(tableName)
                .append(SQLConstants.SEMICOLON_LINE_SEPARATOR)
                .append(ddl).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        context.write(sqlBuilder.toString());
        if (containData) {
            exportTableData(connection, databaseName, schemaName, tableName, context);
        }
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) throws SQLException {
        return String.format(SQL_TRUNCATE_TABLE, tableName);
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName, boolean copyData) throws SQLException {
        String sql;
        if (copyData) {
            sql = String.format(SQL_COPY_TABLE_DATA, newTableName, tableName);
        } else {
            sql = String.format(SQL_COPY_TABLE_STRUCTURE, newTableName, tableName);
        }
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQLConstants.DROP_TABLE_SQL_PREFIX + "%s", tableName);
    }

    @Override
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName,
            TaskExecutionContext context) {
        int batchSize = DEFAULT_EXPORT_BATCH_SIZE;
        context.write(String.format(RECORD_TITLE, tableName));
        logTableQueryStarted(context, tableName);
        AtomicLong exportedRows = new AtomicLong();
        List<String> keyColumns = exportKeyColumns(connection, databaseName, schemaName, tableName);
        if (!keyColumns.isEmpty() && !exportTableDataByKeyset(connection, databaseName, schemaName, tableName,
                keyColumns, batchSize, context, exportedRows)) {
            // Keyset could not continue (for example a key column vanished from the result set);
            // finish the table with the offset path from exactly the row count exported so far.
            exportTableDataByOffset(connection, databaseName, schemaName, tableName, batchSize,
                    exportedRows.get(), context, exportedRows);
        } else if (keyColumns.isEmpty()) {
            exportTableDataByOffset(connection, databaseName, schemaName, tableName, batchSize, FIRST_OFFSET,
                    context, exportedRows);
        }
        logTableQueryCompleted(context, tableName, exportedRows.get());
    }

    /**
     * Single ordered key usable for seek pagination, or empty when the table must use offset paging.
     */
    private List<String> exportKeyColumns(Connection connection, String databaseName, String schemaName,
            String tableName) {
        try {
            List<PrimaryKey> primaryKeys = DefaultSQLExecutor.getInstance()
                    .getPrimaryKeys(connection, databaseName, schemaName, tableName);
            if (primaryKeys == null || primaryKeys.isEmpty()) {
                return List.of();
            }
            List<PrimaryKey> ordered = new ArrayList<>(primaryKeys);
            ordered.sort(Comparator.comparing(
                    (PrimaryKey key) -> key.getKeySeq() == null ? 0 : key.getKeySeq()));
            return ordered.stream().map(PrimaryKey::getColumnName).filter(StringUtils::isNotBlank).toList();
        } catch (Exception e) {
            log.warn("Could not read primary keys for export of {}", tableName, e);
            return List.of();
        }
    }

    /**
     * @return {@code true} when the whole table was exported by keyset pages; {@code false} when the
     *         caller must resume with offset paging from the rows exported so far
     */
    private boolean exportTableDataByKeyset(Connection connection, String databaseName, String schemaName,
            String tableName, List<String> keyColumns, int batchSize, TaskExecutionContext context,
            AtomicLong exportedRows) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        List<KeyBound> bounds = List.of();
        while (true) {
            String pageSql = sqlBuilder.dql().buildKeysetPageLimit(KeysetPageLimitRequest.builder()
                    .databaseName(databaseName)
                    .schemaName(schemaName)
                    .tableName(tableName)
                    .keyColumns(keyColumns)
                    .bounds(bounds)
                    .fetchSize(batchSize)
                    .build());
            KeysetPage page = new KeysetPage(keyColumns);
            try {
                DefaultSQLExecutor.getInstance().fetchAllTableRecords(FetchAllTableRecordsRequest.builder()
                        .connection(connection)
                        .sql(pageSql)
                        .batchSize(batchSize)
                        .statementListener(context)
                        .cancellationChecker(context::checkCancelled)
                        .consumer(resultSet -> readKeysetPage(resultSet, page, sqlBuilder, valueProcessor,
                                tableName, context, exportedRows)).build());
            } catch (Exception e) {
                if (page.rowCount == 0 && bounds.isEmpty()) {
                    log.warn("Keyset export failed for {} before any row; falling back to offset paging",
                            tableName, e);
                    return false;
                }
                throw e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
            }
            if (page.rowCount == 0) {
                return true;
            }
            if (!page.keysFound) {
                return page.rowCount < batchSize;
            }
            bounds = page.cursorBounds();
            if (page.rowCount < batchSize) {
                return true;
            }
        }
    }

    private void readKeysetPage(ResultSet resultSet, KeysetPage page, ISqlBuilder sqlBuilder,
            IValueProcessor valueProcessor, String tableName, TaskExecutionContext context,
            AtomicLong exportedRows) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> columnList = ResultSetUtils.getRsHeader(resultSet);
        page.resolveKeyIndexes(columnList);
        List<List<String>> rows = new ArrayList<>();
        long rowsBytes = 0;
        while (resultSet.next()) {
            context.checkCancelled();
            page.rowCount++;
            exportedRows.incrementAndGet();
            List<String> values = new ArrayList<>(metaData.getColumnCount());
            for (int index = 1; index <= metaData.getColumnCount(); index++) {
                JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, index, false);
                values.add(valueProcessor.getJdbcSqlValueString(jdbcDataValue));
            }
            page.rememberCursor(values);
            rows.add(values);
            rowsBytes += values.stream().mapToInt(value -> value == null ? 4 : value.length() + 2).sum();
            if (rows.size() >= SQL_STATEMENT_MAX_ROWS || rowsBytes >= SQL_STATEMENT_MAX_BYTES) {
                writeInsertStatement(sqlBuilder, tableName, columnList, rows, context);
                rows.clear();
                rowsBytes = 0;
            }
            if (exportedRows.get() % TaskConstants.EXPORT_LOG_ROW_INTERVAL == 0) {
                logRowsExported(context, tableName, exportedRows.get());
            }
        }
        if (!rows.isEmpty()) {
            writeInsertStatement(sqlBuilder, tableName, columnList, rows, context);
        }
    }

    private void writeInsertStatement(ISqlBuilder sqlBuilder, String tableName, List<String> columnList,
            List<List<String>> rows, TaskExecutionContext context) {
        context.write(sqlBuilder.dml().buildBatchInsert(MultiInsertSqlRequest.builder()
                .tableName(tableName)
                .columnList(columnList)
                .valueLists(rows)
                .build()) + SQLConstants.SEMICOLON);
    }

    private void exportTableDataByOffset(Connection connection, String databaseName, String schemaName,
            String tableName, int batchSize, long startOffset, TaskExecutionContext context,
            AtomicLong exportedRows) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        String tableQuerySql = sqlBuilder.dql().buildSelectTable(databaseName, schemaName, tableName);
        AtomicReference<Boolean> finish = new AtomicReference<>(false);
        long offset = startOffset;
        int page = (int) (startOffset / batchSize) + FIRST_PAGE;
        while (!finish.get()) {
            String pageSql = sqlBuilder.dql().buildPageLimit(PageLimitRequest.builder()
                    .sql(tableQuerySql)
                    .offset((int) offset)
                    .pageNo(page)
                    .pageSize(batchSize)
                    .build());
            DefaultSQLExecutor.getInstance().fetchAllTableRecords(FetchAllTableRecordsRequest.builder()
                    .connection(connection)
                    .sql(pageSql)
                    .batchSize(batchSize)
                    .statementListener(context)
                    .cancellationChecker(context::checkCancelled)
                    .consumer(resultSet -> {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<String> columnList = ResultSetUtils.getRsHeader(resultSet);
                List<String> valueList = new ArrayList<>();
                int n = 0;
                while (resultSet.next()) {
                    n++;
                    long currentExportedRows = exportedRows.incrementAndGet();
                    for (int i = 1; i <= metaData.getColumnCount(); i++) {
                        JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, i, false);
                        String valueString = valueProcessor.getJdbcSqlValueString(jdbcDataValue);
                        valueList.add(valueString);
                    }
                    String insertSql = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                            .tableName(tableName)
                            .columnList(columnList)
                            .valueList(valueList)
                            .build());
                    context.write(insertSql + SQLConstants.SEMICOLON);
                    valueList.clear();
                    if (currentExportedRows % TaskConstants.EXPORT_LOG_ROW_INTERVAL == 0) {
                        logRowsExported(context, tableName, currentExportedRows);
                    }
                }
                if (n < batchSize) {
                    finish.set(true);
                }
            }).build());
            page++;
            offset = offset + batchSize;
        }
    }

    /**
     * Per-page cursor state for the keyset export path.
     */
    private static final class KeysetPage {

        private final List<String> keyColumns;

        private final List<String> cursorValues;

        private final int[] keyIndexes;

        private long rowCount;

        private boolean keysFound = true;

        private KeysetPage(List<String> keyColumns) {
            this.keyColumns = keyColumns;
            this.cursorValues = new ArrayList<>(keyColumns.size());
            this.keyIndexes = new int[keyColumns.size()];
            for (int index = 0; index < keyIndexes.length; index++) {
                keyIndexes[index] = -1;
            }
        }

        private void resolveKeyIndexes(List<String> columnList) {
            if (keyIndexes[0] >= 0) {
                return;
            }
            for (int key = 0; key < keyColumns.size(); key++) {
                for (int column = 0; column < columnList.size(); column++) {
                    if (keyColumns.get(key).equalsIgnoreCase(columnList.get(column))) {
                        keyIndexes[key] = column;
                        break;
                    }
                }
                if (keyIndexes[key] < 0) {
                    keysFound = false;
                    return;
                }
            }
        }

        private void rememberCursor(List<String> values) {
            if (!keysFound) {
                return;
            }
            cursorValues.clear();
            for (int key = 0; key < keyIndexes.length; key++) {
                cursorValues.add(values.get(keyIndexes[key]));
            }
        }

        private List<KeyBound> cursorBounds() {
            List<KeyBound> bounds = new ArrayList<>(keyColumns.size());
            for (int key = 0; key < keyColumns.size(); key++) {
                bounds.add(new KeyBound(keyColumns.get(key), cursorValues.get(key), true));
            }
            return bounds;
        }
    }

    protected void exportTableData(Connection connection, String databaseName, String schemaName, String tableName,
            TaskExecutionContext context, int batchSize) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        String tableQuerySql = sqlBuilder.dql().buildSelectTable(databaseName, schemaName, tableName);
        AtomicLong exportedRows = new AtomicLong();
        logTableQueryStarted(context, tableName);
        DefaultSQLExecutor.getInstance().fetchAllTableRecords(FetchAllTableRecordsRequest.builder()
                .connection(connection)
                .sql(tableQuerySql)
                .batchSize(batchSize)
                .statementListener(context)
                .cancellationChecker(context::checkCancelled)
                .consumer(resultSet -> {
            ResultSetMetaData metaData = resultSet.getMetaData();
            List<String> columnList = ResultSetUtils.getRsHeader(resultSet);
            List<String> valueList = new ArrayList<>();
            context.write(String.format(RECORD_TITLE, tableName));
            while (resultSet.next()) {
                long currentExportedRows = exportedRows.incrementAndGet();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
                    JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, i, false);
                    String valueString = valueProcessor.getJdbcSqlValueString(jdbcDataValue);
                    valueList.add(valueString);
                }
                String insertSql = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                        .tableName(tableName)
                        .columnList(columnList)
                        .valueList(valueList)
                        .build());
                context.write(insertSql + ";");
                valueList.clear();
                if (currentExportedRows % TaskConstants.EXPORT_LOG_ROW_INTERVAL == 0) {
                    logRowsExported(context, tableName, currentExportedRows);
                }
            }

        }).build());
        logTableQueryCompleted(context, tableName, exportedRows.get());
    }

    @Override
    public void dropView(Connection connection, String databaseName, String schemaName, String viewName) {

    }
}
