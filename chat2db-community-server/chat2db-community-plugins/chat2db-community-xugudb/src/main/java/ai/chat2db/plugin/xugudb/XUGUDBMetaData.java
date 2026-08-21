package ai.chat2db.plugin.xugudb;

import ai.chat2db.plugin.xugudb.builder.XUGUDBSqlBuilder;
import ai.chat2db.plugin.xugudb.enums.type.XUGUDBColumnTypeEnum;
import ai.chat2db.plugin.xugudb.enums.type.XUGUDBDefaultValueEnum;
import ai.chat2db.plugin.xugudb.enums.type.XUGUDBIndexTypeEnum;
import ai.chat2db.plugin.xugudb.identifier.XugudbIdentifierProcessor;
import ai.chat2db.plugin.xugudb.value.XugudbValueProcessor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.community.domain.api.model.account.*;
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
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static ai.chat2db.spi.util.SortUtils.sortDatabase;
import static ai.chat2db.plugin.xugudb.constant.XUGUDBMetaDataConstants.*;

public class XUGUDBMetaData extends DefaultMetaService implements IDbMetaData {

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return XugudbIdentifierProcessor.INSTANCE;
    }

    @Override
    public IValueProcessor getValueProcessor() {
        return new XugudbValueProcessor();
    }

    @Override
    public List<Database> databases(Connection connection) {
        List<Database> databases = DefaultSQLExecutor.getInstance().databases(connection);
        return sortDatabase(databases, SYSTEM_DATABASES, connection);
    }

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        String sql = "select s.schema_name, db.db_name from all_schemas s left join all_databases db on db.db_id = s.db_id where db.db_name = '" + getSQLIdentifierProcessor().escapeString(databaseName) + "'";
        List<Schema> schemas = DefaultSQLExecutor.getInstance().execute(connection,
                sql, resultSet -> {
                    List<Schema> databases = new ArrayList<>();
                    while (resultSet.next()) {
                        Schema schema = new Schema();
                        String name = resultSet.getString("schema_name");
                        String catalogName = resultSet.getString("db_name");
                        schema.setName(name);
                        schema.setDatabaseName(catalogName);
                        databases.add(schema);
                    }
                    return databases;
                });
        return schemas;
    }

    private String format(String tableName) {
        return XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = """
                SELECT
                    (SELECT dbms_metadata.get_ddl('%s.%s') FROM dual) AS ddl
                FROM dual;
                """;
        StringBuilder ddlBuilder = new StringBuilder();
        String tableDDLSql = String.format(sql, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(tableName));
        DefaultSQLExecutor.getInstance().execute(connection, tableDDLSql, resultSet -> {
            if (resultSet.next()) {
                String ddl = resultSet.getString("ddl");
                ddlBuilder.append(ddl).append("\n");
            }
        });
        return ddlBuilder.toString();
    }




    @Override
    public List<Function> functions(Connection connection, String databaseName, String schemaName) {
        List<Function> functions = new ArrayList<>();
        String sql = String.format(FUNCTIONS_SQL, getSQLIdentifierProcessor().escapeString(databaseName), getSQLIdentifierProcessor().escapeString(schemaName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Function function = new Function();
                function.setDatabaseName(databaseName);
                function.setSchemaName(schemaName);
                function.setFunctionName(resultSet.getString("PROC_NAME"));
                functions.add(function);
            }
            return functions;
        });
    }



    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {

        String sql = String.format(ROUTINES_SQL, getSQLIdentifierProcessor().escapeString(databaseName), getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(functionName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            StringBuilder sb = new StringBuilder();
            while (resultSet.next()) {
                sb.append(resultSet.getString("DEFINE") + "\n");
            }
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            function.setFunctionBody(sb.toString());
            return function;

        });

    }



    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        String sql = String.format(PROCEDURE_SQL, getSQLIdentifierProcessor().escapeString(databaseName), getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(procedureName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            StringBuilder sb = new StringBuilder();
            while (resultSet.next()) {
                sb.append(resultSet.getString("DEFINE") + "\n");
            }
            Procedure procedure = new Procedure();
            procedure.setDatabaseName(databaseName);
            procedure.setSchemaName(schemaName);
            procedure.setProcedureName(procedureName);
            procedure.setProcedureBody(sb.toString());
            return procedure;
        });
    }




    @Override
    public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
        List<Procedure> procedures = new ArrayList<>();
        String sql = String.format(PROCEDURES_SQL, getSQLIdentifierProcessor().escapeString(databaseName), getSQLIdentifierProcessor().escapeString(schemaName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Procedure procedure = new Procedure();
                procedure.setDatabaseName(databaseName);
                procedure.setSchemaName(schemaName);
                procedure.setProcedureName(resultSet.getString("PROC_NAME"));
                procedures.add(procedure);
            }
            return procedures;
        });
    }






    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        String sql = String.format(TRIGGER_SQL_LIST, getSQLIdentifierProcessor().escapeString(databaseName), getSQLIdentifierProcessor().escapeString(schemaName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Trigger trigger = new Trigger();
                trigger.setTriggerName(resultSet.getString("trig_name"));
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

        String sql = String.format(TRIGGER_SQL, getSQLIdentifierProcessor().escapeString(databaseName), getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(triggerName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(schemaName);
            trigger.setTriggerName(triggerName);
            if (resultSet.next()) {
                trigger.setTriggerBody(resultSet.getString("DEFINE"));
            }
            return trigger;
        });
    }



    @Override
    public List<Table> views(Connection connection, String databaseName, String schemaName) {
        String sql = String.format(VIEW_SQL_LIST, getSQLIdentifierProcessor().escapeString(databaseName), getSQLIdentifierProcessor().escapeString(schemaName));
        List<Table> tables = new ArrayList<>();
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            while (resultSet.next()) {
                table.setName(resultSet.getString("VIEW_NAME"));
                table.setDdl(resultSet.getString("DEFINE"));
                tables.add(table);
            }
            return tables;
        });

    }



    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_SQL, getSQLIdentifierProcessor().escapeString(databaseName), getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(viewName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("DEFINE"));
            }
            return table;
        });
    }



    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(INDEX_SQL, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(tableName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap();
            while (resultSet.next()) {
                String keyName = resultSet.getString("INDEX_NAME");
                TableIndex tableIndex = map.get(keyName);
                if (tableIndex != null) {
                    tableIndex.setColumnList(getTableIndexColumn(resultSet));
                } else {
                    TableIndex index = new TableIndex();
                    index.setDatabaseName(databaseName);
                    index.setSchemaName(schemaName);
                    index.setTableName(tableName);
                    index.setName(keyName);
                    index.setUnique((resultSet.getBoolean("IS_UNIQUE")));
                    index.setColumnList(getTableIndexColumn(resultSet));
                    if (resultSet.getBoolean("IS_PRIMARY")) {
                        index.setType(XUGUDBIndexTypeEnum.PRIMARY_KEY.getName());
                    } else if (index.getUnique()) {
                        index.setType(XUGUDBIndexTypeEnum.UNIQUE.getName());
                    } else if ("BTREE".equalsIgnoreCase(resultSet.getString("INDEX_TYPE"))) {
                        index.setType(XUGUDBIndexTypeEnum.BTREE.getName());
                    } else {
                        index.setType(XUGUDBIndexTypeEnum.NORMAL.getName());
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });

    }

    private List<TableIndexColumn> getTableIndexColumn(ResultSet resultSet) throws SQLException {
        List<TableIndexColumn> tableIndexColumnList = new ArrayList<>();
        String[] keys = resultSet.getString("KEYS").split(",");
        for (String key : keys) {
            TableIndexColumn tableIndexColumn = new TableIndexColumn();
            tableIndexColumn.setColumnName(key);
            tableIndexColumn.setOrdinalPosition((short) 0);
            tableIndexColumnList.add(tableIndexColumn);
        }
        return tableIndexColumnList;
    }




    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(SELECT_TABLE_COLUMNS, getSQLIdentifierProcessor().escapeString(databaseName), getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(tableName));
        List<TableColumn> tableColumns = new ArrayList<>();
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                TableColumn column = new TableColumn();
                column.setDatabaseName(databaseName);
                column.setTableName(tableName);
                column.setOldName(resultSet.getString("COL_NAME"));
                column.setName(resultSet.getString("COL_NAME"));
                if (resultSet.getBoolean("VARYING")) {
                    if (resultSet.getString("TYPE_NAME").toUpperCase(Locale.ROOT).equals(XUGUDBColumnTypeEnum.CHAR.name())) {
                        column.setColumnType("VAR" + resultSet.getString("TYPE_NAME").toUpperCase(Locale.ROOT));
                    } else {
                        column.setColumnType(resultSet.getString("TYPE_NAME").toUpperCase(Locale.ROOT));
                    }
                } else {
                    column.setColumnType(resultSet.getString("TYPE_NAME").toUpperCase(Locale.ROOT));
                }
                column.setDefaultValue(resultSet.getString("DEF_VAL"));
                column.setComment(resultSet.getString("COMMENTS"));
                column.setNullable(resultSet.getBoolean("NOT_NULL") ? 0 : 1);
                column.setOrdinalPosition(resultSet.getInt("SCALE"));
                column.setColumnSize(resultSet.getInt("SCALE") == -1 ? null : resultSet.getInt("SCALE"));
                tableColumns.add(column);
            }
            return tableColumns;
        });
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new XUGUDBSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(XUGUDBColumnTypeEnum.getTypes())
                .charsets(Lists.newArrayList())
                .collations(Lists.newArrayList())
                .indexTypes(XUGUDBIndexTypeEnum.getIndexTypes())
                .defaultValues(XUGUDBDefaultValueEnum.getDefaultValues())
                .build();
    }

    @Override
    public String getMetaDataName(String... names) {
        if (Arrays.stream(names).count() > 1) {
            return Arrays.stream(names).skip(1).filter(StringUtils::isNotBlank)
                    .map(XugudbIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                    .collect(Collectors.joining("."));
        }
        return Arrays.stream(names).filter(StringUtils::isNotBlank)
                .map(XugudbIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(Collectors.joining("."));
    }


    @Override
    public List<String> getSystemSchemas() {
        return SYSTEM_SCHEMAS;
    }

}
