package ai.chat2db.plugin.snowflake;

import ai.chat2db.plugin.snowflake.builder.SnowflakeSqlBuilder;
import ai.chat2db.plugin.snowflake.enums.type.*;
import ai.chat2db.plugin.snowflake.identifier.SnowflakeIdentifierProcessor;
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
import ai.chat2db.spi.util.SortUtils;
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.snowflake.constant.SnowflakeMetaDataConstants.*;
public class SnowflakeMetaData extends DefaultMetaService implements IDbMetaData {

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return SnowflakeIdentifierProcessor.INSTANCE;
    }

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = DefaultSQLExecutor.getInstance().schemas(connection, databaseName, null);
        return SortUtils.sortSchema(schemas, SYSTEM_SCHEMAS);
    }




    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_SQL, getSQLIdentifierProcessor().escapeString(databaseName),
                getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(viewName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("DEFINITION").substring(resultSet.getString("DEFINITION").indexOf("as") + 3));
            }
            return table;
        });
    }


    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(SnowflakeColumnTypeEnum.getTypes())
                .charsets(SnowflakeCharsetEnum.getCharsets())
                .collations(SnowflakeCollationEnum.getCollations())
                .indexTypes(SnowflakeIndexTypeEnum.getIndexTypes())
                .defaultValues(SnowflakeDefaultValueEnum.getDefaultValues())
                .build();
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new SnowflakeSqlBuilder();
    }

    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(SnowflakeIdentifierProcessor.INSTANCE::quoteIdentifierAlways).collect(Collectors.joining("."));
    }

    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        StringBuilder queryBuf = new StringBuilder(SQL_SHOW_PRIMARY_KEYS);
        queryBuf.append(SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
        return DefaultSQLExecutor.getInstance().execute(connection, queryBuf.toString(), resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap();
            while (resultSet.next()) {
                String keyName = resultSet.getString("constraint_name");
                TableIndex tableIndex = map.get(keyName);
                if (tableIndex != null) {
                    List<TableIndexColumn> columnList = tableIndex.getColumnList();
                    columnList.add(getTableIndexColumn(resultSet));
                    columnList = columnList.stream().sorted(Comparator.comparing(TableIndexColumn::getOrdinalPosition))
                            .collect(Collectors.toList());
                    tableIndex.setColumnList(columnList);
                } else {
                    TableIndex index = new TableIndex();
                    index.setDatabaseName(databaseName);
                    index.setSchemaName(schemaName);
                    index.setTableName(tableName);
                    index.setName(keyName);
                    index.setType(SnowflakeIndexTypeEnum.PRIMARY_KEY.getName());
                    index.setComment(resultSet.getString("comment"));
                    List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
                    tableIndexColumns.add(getTableIndexColumn(resultSet));
                    index.setColumnList(tableIndexColumns);
                    if ("PRIMARY".equalsIgnoreCase(keyName)) {
                        index.setType(SnowflakeIndexTypeEnum.PRIMARY_KEY.getName());
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });
    }

    private TableIndexColumn getTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString("column_name"));
        tableIndexColumn.setOrdinalPosition(resultSet.getShort("key_sequence"));


        return tableIndexColumn;
    }


    @Override
    public String tableDDL(Connection connection, @NotEmpty String databaseName, String schemaName,
                           @NotEmpty String tableName) {
        if (StringUtils.isBlank(tableName)) {
            return "";
        }
        String sql = String.format(GET_TABLE_DDL_SQL, getSQLIdentifierProcessor().escapeString(databaseName),
                getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(tableName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(1);
            } else {
                return "";
            }
        });
    }



    @Override
    public List<Function> functions(Connection connection, String databaseName, String schemaName) {
        List<Function> functions = new ArrayList<>();
        String sql = String.format(OBJECT_SQL, SnowflakeIdentifierProcessor.escapeIdentifier(schemaName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Function function = new Function();
                function.setDatabaseName(databaseName);
                function.setSchemaName(schemaName);
                function.setFunctionName(resultSet.getString("name"));
                functions.add(function);
            }
            return functions;
        });
    }



    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {

        String sql = String.format(ROUTINES_SQL, getSQLIdentifierProcessor().escapeString(schemaName),
                getSQLIdentifierProcessor().escapeString(functionName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            if (resultSet.next()) {
                function.setSpecificName(resultSet.getString("FUNCTION_NAME"));
                function.setRemarks(resultSet.getString("COMMENT"));
                function.setFunctionBody(resultSet.getString("FUNCTION_DEFINITION"));
            }
            return function;
        });

    }

    public static String format(String tableName) {
        return SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }
}
