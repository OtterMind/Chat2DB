package ai.chat2db.spi;

import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.model.async.*;
import ai.chat2db.community.domain.api.config.*;
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
import ai.chat2db.community.domain.api.model.async.AsyncContext;
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

    private static final String EXPORT_TABLES_MESSAGE = ":Exporting tables";
    private static final String EXPORT_TABLE_MESSAGE = ":Exporting table ";
    private static final String EXPORT_TABLE_DATA_MESSAGE = ":Exporting table data ";
    private static final String EXPORT_PAGE_MESSAGE = " Exporting ";
    private static final String EXPORT_PAGE_SUFFIX = " page";
    private static final String EXPORT_OFFSET_MESSAGE = " offset:";
    private static final String EXPORT_TABLE_ERROR_LOG = "export table error";
    private static final String EXPORT_TABLE_ERROR_MESSAGE = "export table %s error:%s";


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
    public void exportDatabase(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        asyncContext.write(String.format(EXPORT_TITLE, DateUtil.format(new Date(), NORM_DATETIME_PATTERN)));
        asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORT_TABLES_MESSAGE);
        exportTables(connection, databaseName, schemaName, asyncContext);
        asyncContext.setProgress(50);
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        asyncContext.write(SQL_SET_FOREIGN_KEY_CHECKS_DISABLED);
        List<Table> tables = Chat2DBContext.getDbMetaData().tables(connection, new TablesRequest(databaseName, schemaName, null));
        for (Table table : tables) {
            exportTable(connection, databaseName, schemaName, table.getName(), asyncContext);
        }
        asyncContext.write(SQL_SET_FOREIGN_KEY_CHECKS_ENABLED);
    }

    @Override
    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) throws SQLException {
        asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORT_TABLE_MESSAGE + tableName);
        String ddl = Chat2DBContext.getDbMetaData().tableDDL(connection, new TableMetadataRequest(databaseName, schemaName, tableName));
        try {
            StringBuilder sqlBuilder = new StringBuilder();
            asyncContext.write(String.format(TABLE_TITLE, tableName));
            sqlBuilder.append(SQLConstants.DROP_TABLE_IF_EXISTS_SQL_PREFIX).append(tableName).append(SQLConstants.SEMICOLON_LINE_SEPARATOR)
                    .append(ddl).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            asyncContext.write(sqlBuilder.toString());
            if (asyncContext.isContainsData()) {
                asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORT_TABLE_DATA_MESSAGE + tableName);
                exportTableData(connection, databaseName, schemaName, tableName, asyncContext);
            }
        } catch (Exception e) {
            log.error(EXPORT_TABLE_ERROR_LOG, e);
            asyncContext.error(String.format(EXPORT_TABLE_ERROR_MESSAGE, tableName, e.getMessage()));
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
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        String tableQuerySql = sqlBuilder.dql().buildSelectTable(databaseName, schemaName, tableName);
        int batchSize = DEFAULT_EXPORT_BATCH_SIZE;
        int page = FIRST_PAGE;
        int offset = FIRST_OFFSET;
        AtomicReference<Boolean> finish = new AtomicReference<>(false);
        asyncContext.write(String.format(RECORD_TITLE, tableName));
        while (!finish.get()) {
            asyncContext.info(DateUtil.formatDateTime(DateUtil.date()) + ":" + tableName + EXPORT_PAGE_MESSAGE + page
                    + EXPORT_PAGE_SUFFIX + EXPORT_OFFSET_MESSAGE + offset);
            String pageSql = sqlBuilder.dql().buildPageLimit(PageLimitRequest.builder()
                    .sql(tableQuerySql)
                    .offset(offset)
                    .pageNo(page)
                    .pageSize(batchSize)
                    .build());
            DefaultSQLExecutor.getInstance().fetchAllTableRecords(FetchAllTableRecordsRequest.builder()
                    .connection(connection)
                    .sql(pageSql)
                    .batchSize(batchSize)
                    .consumer(resultSet -> {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<String> columnList = ResultSetUtils.getRsHeader(resultSet);
                List<String> valueList = new ArrayList<>();
                int n = 0;
                while (resultSet.next()) {
                    n++;
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
                    asyncContext.write(insertSql + SQLConstants.SEMICOLON);
                    valueList.clear();
                }
                if (n < batchSize) {
                    finish.set(true);
                }
            }).build());
            page++;
            offset = offset + batchSize;
        }
    }

    protected void exportTableData(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext, int batchSize) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        String tableQuerySql = sqlBuilder.dql().buildSelectTable(databaseName, schemaName, tableName);
        asyncContext.info(DateUtil.formatDateTime(DateUtil.date()) + ":" + tableName + " Exporting DATA");
        DefaultSQLExecutor.getInstance().fetchAllTableRecords(FetchAllTableRecordsRequest.builder()
                .connection(connection)
                .sql(tableQuerySql)
                .batchSize(batchSize)
                .consumer(resultSet -> {
            ResultSetMetaData metaData = resultSet.getMetaData();
            List<String> columnList = ResultSetUtils.getRsHeader(resultSet);
            List<String> valueList = new ArrayList<>();
            asyncContext.write(String.format(RECORD_TITLE, tableName));
            int n = 0;
            while (resultSet.next()) {
                n++;
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
                asyncContext.write(insertSql + ";");
                valueList.clear();
                if (n % 10000 == 0) {
                    asyncContext.info(DateUtil.formatDateTime(DateUtil.date()) + ":" + tableName + " Exporting DATA " + n);
                }
            }

        }).build());
        asyncContext.info(DateUtil.formatDateTime(DateUtil.date()) + ":" + tableName + " Exporting DATA finished");
    }

    @Override
    public void dropView(Connection connection, String databaseName, String schemaName, String viewName) {

    }
}
