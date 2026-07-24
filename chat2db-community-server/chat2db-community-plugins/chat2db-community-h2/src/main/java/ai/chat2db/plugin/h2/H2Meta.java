package ai.chat2db.plugin.h2;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.stream.Collectors;

import ai.chat2db.plugin.h2.builder.H2SqlBuilder;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.model.async.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.SortUtils;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import static ai.chat2db.plugin.h2.constant.H2MetaConstants.*;
@Slf4j
public class H2Meta extends DefaultMetaService implements IDbMetaData {



    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = DefaultSQLExecutor.getInstance().schemas(connection, databaseName, null);
        return SortUtils.sortSchema(schemas, SYSTEM_SCHEMAS);
    }
    @Override
    public String tableDDL(Connection connection, @NotEmpty String databaseName, String schemaName,
        @NotEmpty String tableName) {
        return getDDL(connection, databaseName, schemaName, tableName);
    }

    private String getDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        try (ResultSet columns = connection.getMetaData().getColumns(databaseName, schemaName, tableName, null)) {
            List<String> columnDefinitions = new ArrayList<>();
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String columnType = columns.getString("TYPE_NAME");
                int columnSize = columns.getInt("COLUMN_SIZE");
                String remarks = columns.getString("REMARKS");
                String defaultValue = columns.getString("COLUMN_DEF");
                String nullable = columns.getInt("NULLABLE") == ResultSetMetaData.columnNullable ? "NULL" : "NOT NULL";
                StringBuilder columnDefinition = new StringBuilder();
                columnDefinition.append(columnName).append(" ").append(columnType);
                if (columnSize != 0) {
                    columnDefinition.append("(").append(columnSize).append(")");
                }
                columnDefinition.append(" ").append(nullable);
                if (defaultValue != null) {
                    columnDefinition.append(" DEFAULT ").append(defaultValue);
                }
                if (remarks != null) {
                    columnDefinition.append(SQL_COMMENT).append(remarks).append("'");
                }
                columnDefinitions.add(columnDefinition.toString());
            }
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(databaseName, schemaName, tableName, false,
                false)) {
                Map<String, List<String>> indexMap = new HashMap<>();
                while (indexes.next()) {
                    String indexName = indexes.getString("INDEX_NAME");
                    String columnName = indexes.getString("COLUMN_NAME");
                    if (indexName != null) {
                        if (!indexMap.containsKey(indexName)) {
                            indexMap.put(indexName, new ArrayList<>());
                        }
                        indexMap.get(indexName).add(columnName);
                    }
                }
                StringBuilder createTableDDL = new StringBuilder(SQL_CREATE_TABLE);
                createTableDDL.append(tableName).append(" (\n");
                createTableDDL.append(String.join(",\n", columnDefinitions));
                createTableDDL.append("\n);\n");
                for (Map.Entry<String, List<String>> entry : indexMap.entrySet()) {
                    String indexName = entry.getKey();
                    List<String> columnList = entry.getValue();
                    String indexColumns = String.join(", ", columnList);
                    String createIndexDDL = String.format(SQL_CREATE_INDEX, indexName, tableName,
                        indexColumns);
                    createTableDDL.append(createIndexDDL);
                }
                return createTableDDL.toString();
            }

        } catch (Exception e) {
            log.error("Failed to get table DDL", e);
        }
        return "";
    }



    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
        String functionName) {

        String sql = String.format(ROUTINES_SQL, "FUNCTION", databaseName, functionName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            if (resultSet.next()) {
                function.setSpecificName(resultSet.getString("SPECIFIC_NAME"));
                function.setFunctionBody(resultSet.getString("ROUTINE_DEFINITION"));
            }

            return function;
        });

    }





    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        String sql = String.format(TRIGGER_SQL_LIST, databaseName,schemaName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Trigger trigger = new Trigger();
                trigger.setTriggerName(resultSet.getString("TRIGGER_NAME"));
                trigger.setSchemaName(schemaName);
                trigger.setDatabaseName(databaseName);
                triggers.add(trigger);
            }
            return triggers;
        });
    }

    @Override
    public Trigger trigger(Connection connection, @NotEmpty String databaseName, String schemaName,
        String triggerName) {

        String sql = String.format(TRIGGER_SQL, databaseName, triggerName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(schemaName);
            trigger.setTriggerName(triggerName);
            if (resultSet.next()) {
                trigger.setTriggerBody(resultSet.getString("JAVA_CLASS"));
            }
            return trigger;
        });
    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
        String procedureName) {
        String sql = String.format(ROUTINES_SQL, "PROCEDURE", databaseName, procedureName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Procedure procedure = new Procedure();
            procedure.setDatabaseName(databaseName);
            procedure.setSchemaName(schemaName);
            procedure.setProcedureName(procedureName);
            if (resultSet.next()) {
                procedure.setSpecificName(resultSet.getString("SPECIFIC_NAME"));
                procedure.setProcedureBody(resultSet.getString("ROUTINE_DEFINITION"));
            }
            return procedure;
        });
    }



    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_SQL, databaseName, schemaName, viewName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("VIEW_DEFINITION"));
            }
            return table;
        });
    }
    @Override
    public ISqlBuilder getSqlBuilder() {
        return new H2SqlBuilder();
    }


    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(name -> "\"" + name + "\"").collect(Collectors.joining("."));
    }

    @Override
    public List<String> getSystemSchemas() {
        return SYSTEM_SCHEMAS;
    }
}
