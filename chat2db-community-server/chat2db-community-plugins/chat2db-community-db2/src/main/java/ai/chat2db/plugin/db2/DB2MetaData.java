package ai.chat2db.plugin.db2;

import ai.chat2db.plugin.db2.builder.DB2SqlBuilder;
import ai.chat2db.plugin.db2.enums.type.DB2ColumnTypeEnum;
import ai.chat2db.plugin.db2.enums.type.DB2DefaultValueEnum;
import ai.chat2db.plugin.db2.enums.type.DB2IndexTypeEnum;
import ai.chat2db.plugin.db2.identifier.Db2IdentifierProcessor;
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
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.util.SortUtils;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.db2.constant.SQLConstant.TABLES_SQL;

import static ai.chat2db.plugin.db2.constant.DB2MetaDataConstants.*;
@Slf4j
public class DB2MetaData extends DefaultMetaService implements IDbMetaData {

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return Db2IdentifierProcessor.INSTANCE;
    }

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = DefaultSQLExecutor.getInstance().schemas(connection, databaseName, null);
        return SortUtils.sortSchema(schemas, SYSTEM_SCHEMAS);
    }


    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        try {
            return super.tables(connection, databaseName, schemaName, tableName);
        } catch (Exception e) {
            return DefaultSQLExecutor.getInstance().preExecute(connection, TABLES_SQL, new String[]{schemaName}, resultSet -> {
                ArrayList<Table> tables = new ArrayList<>();
                while (resultSet.next()) {
                    String tabname = resultSet.getString("TABNAME");
                    Table table = new Table();
                    table.setName(tabname);
                    tables.add(table);
                }
                return tables;
            });
        }
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        validateDb2LookName(schemaName);
        validateDb2LookName(tableName);
        String db2LookOptions = String.format(DB2LOOK_OPTIONS, schemaName, tableName);

        log.info("try to execute PROC");
        try (CallableStatement callableStatement = connection.prepareCall(GET_DDL_TOKEN)) {
            callableStatement.setString(1, db2LookOptions);
            callableStatement.registerOutParameter(2, Types.INTEGER);
            callableStatement.executeUpdate();
            int token = callableStatement.getInt(2);
            log.info("ddlToken: {}", token);
            StringBuilder ddlBuilder = new StringBuilder(2048);
            retrieveDDL(connection, token, ddlBuilder);
            cleanDDLToken(connection, token);

            return ddlBuilder.toString();
        } catch (SQLException e) {
            log.error("Failed to get DDL for table {}.{}: {}", schemaName, tableName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Names are JDBC-bound as one db2look option string, but a double quote would still
     * break out of an option argument, so reject such names up front.
     */
    private static void validateDb2LookName(String name) {
        if (name != null && name.contains("\"")) {
            throw new IllegalArgumentException("Invalid DB2 object name for DDL generation: " + name);
        }
    }

    private void retrieveDDL(Connection connection, int token, StringBuilder ddlBuilder) {
        log.info("try to execute SQL");
        try (PreparedStatement preparedStatement = connection.prepareStatement(GET_DDL_SQL)) {
            preparedStatement.setInt(1, token);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    Clob ddl = resultSet.getClob(1);
                    appendClobToDDL(ddl, ddlBuilder);
                }
            }
        } catch (SQLException e) {
            log.error("Error while retrieving DDL SQL for token {}: {}", token, e.getMessage(), e);
        }
    }

    private void appendClobToDDL(Clob ddl, StringBuilder ddlBuilder) throws SQLException {

        if (ddl != null) {
            try {
                long length = ddl.length();
                String ddlString = ddl.getSubString(1, (int) length);
                if (StringUtils.isNotBlank(ddlString)) {
                    ddlBuilder.append(ddlString.trim()).append(";\n\n");
                }
            } finally {
                try {
                    ddl.free();
                } catch (SQLException e) {
                    log.error("Failed to free CLOB resource", e);
                }
            }
        }
    }

    private void cleanDDLToken(Connection connection, int token) {
        log.info("try to clean ddl token");
        try (CallableStatement callableStatement = connection.prepareCall(CLEAN_DDL_TOKEN)) {
            callableStatement.setInt(1, token);
            callableStatement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to clean DDL token: {}", e.getMessage(), e);
        }
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new DB2SqlBuilder();
    }



    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(IDX_SQL, getSQLIdentifierProcessor().escapeString(tableName), getSQLIdentifierProcessor().escapeString(schemaName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap();
            while (resultSet.next()) {
                String keyName = resultSet.getString("INDNAME");
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
                    index.setComment(resultSet.getString("REMARKS"));
                    List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
                    tableIndexColumns.add(getTableIndexColumn(resultSet));
                    index.setColumnList(tableIndexColumns);
                    String uniquerule = resultSet.getString("UNIQUERULE");
                    if ("P".equalsIgnoreCase(uniquerule)) {
                        index.setType(DB2IndexTypeEnum.PRIMARY_KEY.getName());
                        index.setUnique(true);
                    } else if ("U".equalsIgnoreCase(uniquerule)) {
                        index.setType(DB2IndexTypeEnum.UNIQUE.getName());
                        index.setUnique(true);
                    } else {
                        index.setType(DB2IndexTypeEnum.NORMAL.getName());
                        index.setUnique(false);
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });

    }



    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_DDL_SQL, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(viewName));
        Table table = new Table();
        table.setDatabaseName(databaseName);
        table.setSchemaName(schemaName);
        table.setName(viewName);
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("TEXT") + ";");
            }
        });
        return table;
    }



    @Override
    public Function function(Connection connection, String databaseName, String schemaName, String functionName) {
        Function function = new Function();
        function.setDatabaseName(databaseName);
        function.setSchemaName(schemaName);
        function.setFunctionName(functionName);
        String sql = String.format(ROUTINE_DDL_SQL, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(functionName), 'F');
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                function.setFunctionBody(resultSet.getString("TEXT") + ";");
            }
        });
        return function;
    }

    @Override
    public Procedure procedure(Connection connection, String databaseName, String schemaName, String procedureName) {
        Procedure procedure = new Procedure();
        procedure.setDatabaseName(databaseName);
        procedure.setSchemaName(schemaName);
        procedure.setProcedureName(procedureName);
        String sql = String.format(ROUTINE_DDL_SQL, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(procedureName), 'P');
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                procedure.setProcedureBody(resultSet.getString("TEXT") + ";");
            }
        });
        return procedure;
    }

    private TableIndexColumn getTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString("COLNAME"));
        tableIndexColumn.setOrdinalPosition(resultSet.getShort("COLSEQ"));
        String collation = resultSet.getString("COLORDER");
        if ("A".equalsIgnoreCase(collation)) {
            tableIndexColumn.setAscOrDesc("ASC");
        } else if ("D".equalsIgnoreCase(collation)) {
            tableIndexColumn.setAscOrDesc("DESC");
        }
        return tableIndexColumn;
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(DB2ColumnTypeEnum.getTypes())
                .charsets(Lists.newArrayList())
                .collations(Lists.newArrayList())
                .indexTypes(DB2IndexTypeEnum.getIndexTypes())
                .defaultValues(DB2DefaultValueEnum.getDefaultValues())
                .build();
    }

    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(Db2IdentifierProcessor.INSTANCE::quoteIdentifierAlways).collect(Collectors.joining("."));
    }

    @Override
    public List<String> getSystemSchemas() {
        return SYSTEM_SCHEMAS;
    }
}
