package ai.chat2db.plugin.clickhouse;

import ai.chat2db.plugin.clickhouse.builder.ClickHouseSqlBuilder;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseColumnTypeEnum;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseEngineTypeEnum;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseIndexTypeEnum;
import ai.chat2db.plugin.clickhouse.identifier.ClickHouseIdentifierProcessor;
import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
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
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


import static ai.chat2db.plugin.clickhouse.constant.ClickHouseMetaDataConstants.*;
public class ClickHouseMetaData extends DefaultMetaService implements IDbMetaData {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseMetaData.class);

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return ClickHouseIdentifierProcessor.INSTANCE;
    }

    public static String format(String tableName) {
        return ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }

    @Override
    public List<Function> functions(Connection connection, String databaseName, String schemaName) {
        return DefaultSQLExecutor.getInstance().execute(connection, FUNCTION_SQL, resultSet -> {
            List<Function> functions = new ArrayList<>();
            while (resultSet.next()) {
                Function function = new Function();
                function.setFunctionName(resultSet.getString("name"));
                functions.add(function);
            }
            return functions;
        });
    }

    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        final String BASE_SQL = SQL_SELECT_NAME_SYSTEM_TABLES_DATABASE;

        StringBuilder sql = new StringBuilder(BASE_SQL);

        List<String> params = new ArrayList<>();
        params.add(schemaName);
        if (StringUtils.isNotBlank(tableName)) {
            sql.append(SQL_NAME);
            params.add(tableName);
        }

        return DefaultSQLExecutor.getInstance().preExecute(connection, sql.toString(), params.toArray(new String[0]), resultSet -> {
            List<Table> tables = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    String dbName = resultSet.getString("name");
                    Table table = new Table();
                    table.setName(dbName);
                    tables.add(table);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Error processing ResultSet for tables", e);
            }
            return tables;
        });
    }

    @Override
    public List<Table> views(Connection connection, String databaseName, String schemaName) {
        String sql = "select name from system.`tables` WHERE `database`='" + getSQLIdentifierProcessor().escapeString(schemaName) + "' and engine='View'";
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Table> tables = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    String dbName = resultSet.getString("name");
                    Table table = new Table();
                    table.setName(dbName);
                    tables.add(table);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return tables;
        });

    }


    @Override
    public String tableDDL(Connection connection, @NotEmpty String databaseName, String schemaName,
                           @NotEmpty String tableName) {
        String sql = "SHOW CREATE TABLE " + getMetaDataName(schemaName, tableName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString("statement");
            }
            return null;
        });
    }

    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {
        return DefaultSQLExecutor.getInstance().execute(connection, FUNCTION_SQL, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            if (resultSet.next()) {


                function.setFunctionBody(resultSet.getString("ddl"));
            }
            return function;
        });

    }

    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        String sql = String.format(TRIGGER_SQL_LIST, getSQLIdentifierProcessor().escapeString(schemaName));
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

        String sql = String.format(TRIGGER_SQL, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(triggerName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(schemaName);
            trigger.setTriggerName(triggerName);
            if (resultSet.next()) {
                trigger.setTriggerBody(resultSet.getString("ACTION_STATEMENT"));
            }
            return trigger;
        });
    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        String sql = String.format(ROUTINES_SQL, "PROCEDURE", getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(procedureName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Procedure procedure = new Procedure();
            procedure.setDatabaseName(databaseName);
            procedure.setSchemaName(schemaName);
            procedure.setProcedureName(procedureName);
            if (resultSet.next()) {
                procedure.setSpecificName(resultSet.getString("SPECIFIC_NAME"));
                procedure.setRemarks(resultSet.getString("ROUTINE_COMMENT"));
                procedure.setProcedureBody(resultSet.getString("ROUTINE_DEFINITION"));
            }
            return procedure;
        });
    }

    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(SELECT_TABLE_COLUMNS, getSQLIdentifierProcessor().escapeString(tableName), getSQLIdentifierProcessor().escapeString(schemaName));
        List<TableColumn> tableColumns = new ArrayList<>();

        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                TableColumn column = new TableColumn();
                column.setDatabaseName(databaseName);
                column.setTableName(tableName);
                column.setOldName(resultSet.getString("name"));
                column.setName(resultSet.getString("name"));
                String dataType = resultSet.getString("type");
                if (dataType.startsWith("Nullable(")) {
                    dataType = dataType.substring(9, dataType.length() - 1);
                    column.setNullable(1);
                }
                column.setColumnType(dataType);
                column.setDefaultValue(resultSet.getString("default_expression"));
                column.setComment(resultSet.getString("comment"));
                column.setOrdinalPosition(resultSet.getInt("position"));
                column.setDecimalDigits(resultSet.getInt("numeric_scale"));


                setColumnSize(column, dataType);
                tableColumns.add(column);
            }
            return tableColumns;
        });
    }

    private void setColumnSize(TableColumn column, String columnType) {
        try {
            if (columnType.contains("(")) {
                String size = columnType.substring(columnType.indexOf("(") + 1, columnType.indexOf(")"));
                if ("SET".equalsIgnoreCase(column.getColumnType()) || "ENUM".equalsIgnoreCase(column.getColumnType())) {
                    column.setValue(size);
                } else {
                    if (size.contains(",")) {
                        String[] sizes = size.split(",");
                        if (StringUtils.isNotBlank(sizes[0])) {
                            column.setColumnSize(Integer.parseInt(sizes[0]));
                        }
                        if (sizes.length > 1 && StringUtils.isNotBlank(sizes[1])) {
                            column.setDecimalDigits(Integer.parseInt(sizes[1]));
                        }
                    } else {
                        column.setColumnSize(Integer.parseInt(size));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("parse column size failed: {}", columnType, e);
        }
    }

    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_SQL, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(viewName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("create_table_query"));
            }
            return table;
        });
    }


    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        return Lists.newArrayList();
    }

    private List<TableIndexColumn> getTableIndexColumn(ResultSet resultSet) throws SQLException {
        List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
        String name = StringUtils.isBlank(resultSet.getString("column_name")) ? resultSet.getString("expression") : resultSet.getString("column_name");
        if (StringUtils.isNotBlank(name)) {
            String[] split = name.split(",");
            for (String columName : split) {
                TableIndexColumn tableIndexColumn = new TableIndexColumn();
                tableIndexColumn.setColumnName(columName);
                tableIndexColumn.setOrdinalPosition(resultSet.getShort("seq_in_index"));
                tableIndexColumn.setCollation(resultSet.getString("collation"));
                tableIndexColumn.setCardinality(resultSet.getLong("cardinality"));
                tableIndexColumn.setSubPart(resultSet.getLong("sub_part"));
                tableIndexColumns.add(tableIndexColumn);
            }
        }
        return tableIndexColumns;
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new ClickHouseSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(ClickHouseColumnTypeEnum.getTypes())
                .engineTypes(ClickHouseEngineTypeEnum.getTypes())
                .indexTypes(ClickHouseIndexTypeEnum.getIndexTypes())
                .build();
    }

    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(ClickHouseIdentifierProcessor.INSTANCE::quoteIdentifierAlways).collect(Collectors.joining("."));

    }

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> list = DefaultSQLExecutor.getInstance().execute(connection, SQL_SHOW_DATABASES, resultSet -> {
            List<Schema> schemas = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    String dbName = resultSet.getString("name");
                    Schema database = new Schema();
                    database.setName(dbName);
                    schemas.add(database);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return schemas;
        });
        return list;
    }

    @Override
    public List<String> getSystemDatabases() {
        return SYSTEM_DATABASES;
    }


    @Override
    public ICommandExecutor getCommandExecutor() {
        return new ClickHouseExecutor();
    }
}
