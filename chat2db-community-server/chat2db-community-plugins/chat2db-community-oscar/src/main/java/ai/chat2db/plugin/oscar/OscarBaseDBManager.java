package ai.chat2db.plugin.oscar;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.oscar.util.OscarUtils;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.spi.DefaultSQLExecutor;

import java.sql.Connection;
import java.sql.SQLException;

public abstract class OscarBaseDBManager extends DefaultDBManager {
    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.STREAMING_ONLY;
    }


    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return SQLConstants.DROP_TABLE_SQL_PREFIX + qualifiedName(schemaName, tableName);
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return SQLConstants.TRUNCATE_TABLE_SQL_PREFIX + qualifiedName(schemaName, tableName);
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName,
                          String newTableName, boolean copyData) throws SQLException {
        String sql = SQLConstants.CREATE_TABLE_SQL_PREFIX + qualifiedName(schemaName, newTableName)
                + SQLConstants.CREATE_TABLE_AS_SELECT_FROM_SQL + qualifiedName(schemaName, tableName);
        if (!copyData) {
            sql += SQLConstants.EMPTY_RESULT_WHERE_SQL;
        }
        execute(connection, sql);
    }

    @Override
    public void dropTrigger(Connection connection, String databaseName, String schemaName, String triggerName) {
        execute(connection, SQLConstants.DROP_TRIGGER_SQL_PREFIX + qualifiedName(schemaName, triggerName));
    }

    @Override
    public void dropProcedure(Connection connection, String databaseName, String schemaName, String procedureName) {
        execute(connection, SQLConstants.DROP_PROCEDURE_SQL_PREFIX + qualifiedName(schemaName, procedureName));
    }

    @Override
    public void updateProcedure(Connection connection, String databaseName, String schemaName, Procedure procedure)
            throws SQLException {
        execute(connection, procedure.getProcedureBody());
    }

    @Override
    public void dropView(Connection connection, String databaseName, String schemaName, String viewName) {
        execute(connection, SQLConstants.DROP_VIEW_SQL_PREFIX + qualifiedName(schemaName, viewName));
    }

    protected void execute(Connection connection, String sql) {
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

    protected String qualifiedName(String schemaName, String objectName) {
        return OscarUtils.qualifiedName(schemaName, objectName);
    }
}
