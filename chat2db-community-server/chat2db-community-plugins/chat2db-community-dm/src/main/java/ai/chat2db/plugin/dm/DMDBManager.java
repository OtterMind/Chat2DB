package ai.chat2db.plugin.dm;

import ai.chat2db.plugin.dm.enums.type.DMIndexTypeEnum;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static ai.chat2db.plugin.dm.DMMetaData.tableDDL;

import static ai.chat2db.plugin.dm.constant.DMDBManagerConstants.*;
@Slf4j
public class DMDBManager extends DefaultDBManager implements IDbManager {






    private String format(String tableName) {
        return "\"" + tableName + "\"";
    }






    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        asyncContext.info(DateUtil.formatDateTime(new Date()) + ":Exporting tables");
        exportTables(connection, databaseName, schemaName, asyncContext);
        asyncContext.setProgress(70);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + ":Exporting views");
        exportViews(connection, schemaName, asyncContext);
        asyncContext.setProgress(80);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + ":Exporting producers");
        exportProcedures(connection, schemaName, asyncContext);
        asyncContext.setProgress(90);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + ":Exporting triggers");
        exportTriggers(connection, schemaName, asyncContext);
        asyncContext.setProgress(99);

    }

    private void exportTables(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_TABLE_NAME_ALL_TABLES, schemaName);
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                exportTable(connection, databaseName, schemaName, tableName, asyncContext);
            }
        }
    }

    @Override
    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) throws SQLException {
        String tableDDLSql = String.format(tableDDL, tableName, schemaName);
        StringBuilder ddlBuilder = new StringBuilder();
        DefaultSQLExecutor.getInstance().execute(connection, tableDDLSql, resultSet -> {
            if (resultSet.next()) {
                String ddl = resultSet.getString("ddl");
                ddlBuilder.append(ddl).append("\n");
            }
        });
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        List<Table> tables = metaData.tables(connection, new TablesRequest(databaseName, schemaName, tableName));
        if (CollectionUtils.isNotEmpty(tables)) {
            String tableComment = tables.get(0).getComment();
            if (StringUtils.isNotBlank(tableComment)) {
                ddlBuilder.append(SQL_COMMENT_TABLE).append(format(schemaName)).append(".").append(format(tableName))
                        .append(" IS '").append(tableComment.replace("'", "''")).append("'").append(";").append("\n");
            }
        }
        List<TableColumn> columns = metaData.columns(connection,
                new TableMetadataRequest(databaseName, schemaName, tableName));
        if (CollectionUtils.isNotEmpty(columns)) {
            for (TableColumn column : columns) {
                String columnName = column.getName();
                String comment = column.getComment();
                if (StringUtils.isNotBlank(comment)) {
                    ddlBuilder.append(SQL_COMMENT_COLUMN).append(format(schemaName)).append(".").append(format(tableName))
                            .append(".").append(format(columnName)).append(" IS ")
                            .append("'").append(comment.replace("'", "''"))
                            .append("';").append("\n");
                }
            }
        }
        if (!tableName.startsWith("V$")) {
            List<TableIndex> indexes = metaData.indexes(connection,
                    new TableMetadataRequest(databaseName, schemaName, tableName));
            List<String> uniqueConstraintIndexName = null;
            if (CollectionUtils.isNotEmpty(indexes)) {
                try {
                    String sql = "select INDEX_NAME from  sys.all_constraints where OWNER=? and TABLE_NAME=? and CONSTRAINT_TYPE = 'U' ;";
                    uniqueConstraintIndexName = DefaultSQLExecutor.getInstance().preExecute(connection, sql, new String[]{schemaName, tableName}, resultSet -> {
                        List<String> indexNames = new ArrayList<>(5);
                        while (resultSet.next()) {
                            String indexName = resultSet.getString("INDEX_NAME");
                            if (StringUtils.isNotBlank(indexName)) {
                                indexNames.add(indexName);
                            }
                        }
                        return indexNames;
                    });
                } catch (Exception e) {
                    log.error(" get unique constraint index name error", e);
                }
            }
            for (TableIndex index : indexes) {
                String indexName = index.getName();
                if (StringUtils.isNotBlank(indexName) && !StringUtils.equalsIgnoreCase("primary", index.getType())
                        && (CollectionUtils.isNotEmpty(uniqueConstraintIndexName) && !uniqueConstraintIndexName.contains(indexName))) {
                    String sql = "select DBMS_METADATA.GET_DDL('INDEX','%s') as INDEX_DDL";
                    try {
                        DefaultSQLExecutor.getInstance().execute(connection, String.format(sql, indexName), resultSet -> {
                            if (resultSet.next()) {
                                ddlBuilder.append(resultSet.getString("INDEX_DDL")).append("\n");
                            }
                        });
                    } catch (Exception e) {
                        log.warn("Failed to get the DDL of the index: {}", indexName, e);
                        DMIndexTypeEnum indexTypeEnum = DMIndexTypeEnum.getByType(index.getType());
                        if (indexTypeEnum != null) {
                            ddlBuilder.append("\n").append(indexTypeEnum.buildIndexScript(index)).append(";");
                        }
                    }
                }
            }
        }
        asyncContext.write(ddlBuilder.toString());
        if (asyncContext.isContainsData()) {
            exportTableData(connection, databaseName, schemaName, tableName, asyncContext);
        }
    }


    private void exportViews(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, schemaName, null, new String[]{"VIEW"})) {
            while (resultSet.next()) {
                String viewName = resultSet.getString("TABLE_NAME");
                exportView(connection, viewName, schemaName, asyncContext);
            }
        }
    }

    private void exportView(Connection connection, String viewName, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_DBMS_METADATA_GET_DDL, viewName, schemaName);
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("ddl")).append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    private void exportProcedures(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getProcedures(null, schemaName, null)) {
            while (resultSet.next()) {
                String procedureName = resultSet.getString("PROCEDURE_NAME");
                exportProcedure(connection, schemaName, procedureName, asyncContext);
            }
        }
    }

    private void exportProcedure(Connection connection, String schemaName, String procedureName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(ROUTINES_SQL, "PROC", schemaName, procedureName);
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("TEXT")).append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    private void exportTriggers(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(TRIGGER_SQL_LIST, schemaName);
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String triggerName = resultSet.getString("TRIGGER_NAME");
                exportTrigger(connection, schemaName, triggerName, asyncContext);
            }
        }
    }

    private void exportTrigger(Connection connection, String schemaName, String triggerName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(TRIGGER_SQL, schemaName, triggerName);
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("TRIGGER_BODY")).append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    @Override
    public void connectDatabase(Connection connection, String database) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (ObjectUtils.anyNull(connectInfo) || StringUtils.isEmpty(connectInfo.getSchemaName())) {
            return;
        }
        String schemaName = connectInfo.getSchemaName();
        try {
            DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_SET_SCHEMA, schemaName));
        } catch (SQLException e) {
            log.error("connectDatabase error", e);
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE_EXISTS, tableName);
    }
}
