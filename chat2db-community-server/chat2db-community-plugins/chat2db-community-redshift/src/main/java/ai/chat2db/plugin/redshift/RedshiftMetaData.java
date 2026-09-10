package ai.chat2db.plugin.redshift;

import ai.chat2db.plugin.postgresql.PostgreSQLMetaData;
import ai.chat2db.plugin.redshift.builder.RedshiftSqlBuilder;
import ai.chat2db.plugin.redshift.identifier.RedshiftIdentifierProcessor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.community.domain.api.model.metadata.Function;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.Trigger;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.List;


import static ai.chat2db.plugin.redshift.constant.RedshiftMetaDataConstants.*;
@Slf4j
public class RedshiftMetaData extends PostgreSQLMetaData implements IDbMetaData {

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return RedshiftIdentifierProcessor.INSTANCE;
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        // Redshift rejects the PostgreSQL LC_CTYPE/LC_COLLATE clauses (and the
        // dangling WITH the base prepends); use the Redshift builder.
        return new RedshiftSqlBuilder();
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = buildShowCreateTableSql(schemaName, tableName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
            return null;
        });
    }

    static String buildShowCreateTableSql(String schemaName, String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("Redshift table name must not be empty");
        }
        String qualifiedName = schemaName == null || schemaName.isEmpty()
                ? RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName)
                : RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName) + "."
                + RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
        return "SHOW CREATE TABLE " + qualifiedName;
    }

    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        return DefaultSQLExecutor.getInstance().indexes(connection, StringUtils.isEmpty(databaseName) ? null : databaseName, StringUtils.isEmpty(schemaName) ? null : schemaName, tableName);
    }

    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {
        return DefaultSQLExecutor.getInstance().preExecute(connection, FUNCTION_DEFINITION, new String[]{functionName,schemaName}, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            if (resultSet.next()) {
                function.setFunctionBody(resultSet.getString("definition"));
            }
            return function;
        });

    }

    @Override
    public Trigger trigger(Connection connection, @NotEmpty String databaseName, String schemaName,
                           String triggerName) {

        return DefaultSQLExecutor.getInstance().preExecute(connection, FUNCTION_DEFINITION, new String[]{triggerName, schemaName}, resultSet -> {
            Trigger function = new Trigger();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setTriggerName(triggerName);
            if (resultSet.next()) {
                function.setTriggerBody(resultSet.getString("definition"));
            }
            return function;
        });
    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        return DefaultSQLExecutor.getInstance().preExecute(connection, FUNCTION_DEFINITION, new String[]{ procedureName,schemaName}, resultSet -> {
            Procedure procedure = new Procedure();
            procedure.setDatabaseName(databaseName);
            procedure.setSchemaName(schemaName);
            procedure.setProcedureName(procedureName);
            if (resultSet.next()) {
                procedure.setProcedureBody(resultSet.getString("definition"));
            }
            return procedure;
        });
    }

}
