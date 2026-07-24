package ai.chat2db.plugin.sundb;

import ai.chat2db.plugin.sundb.builder.SUNDBSqlBuilder;
import ai.chat2db.plugin.sundb.enums.type.SUNDBColumnTypeEnum;
import ai.chat2db.plugin.sundb.enums.type.SUNDBDefaultValueEnum;
import ai.chat2db.plugin.sundb.enums.type.SUNDBIndexTypeEnum;
import ai.chat2db.plugin.sundb.enums.type.SUNDBObjectTypeEnum;
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
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.sundb.constant.SUNDBMetaDataConstants.*;
public class SUNDBMetaData extends DefaultMetaService implements IDbMetaData {


    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = DefaultSQLExecutor.getInstance().schemas(connection, databaseName, null);
        return SortUtils.sortSchema(schemas, SYSTEM_SCHEMAS);
    }

    private String format(String tableName) {
        return "\"" + tableName + "\"";
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = "SELECT * FROM \"%s\".\"%s\" LIMIT 1";
        StringBuilder ddlBuilder = new StringBuilder();
        String tableDDLSql = String.format(sql, schemaName, tableName);

        try {
            Integer Scale;
            try (PreparedStatement tableDdlStatement = connection.prepareStatement(tableDDLSql);
                 ResultSet resultSet = tableDdlStatement.executeQuery()) {
            ResultSetMetaData rsMetaData = resultSet.getMetaData();
            int colCount = rsMetaData.getColumnCount();
            ddlBuilder.append(SQL_CREATE_TABLE).append("\"").append(schemaName).append("\"").append(".")
                    .append("\"").append(tableName).append("\" \n( ");
            int i = 1;
            while (true) {
                if (i >= colCount) {
                    ddlBuilder.append(rsMetaData.getColumnName(colCount) + " ");
                    ddlBuilder.append(rsMetaData.getColumnTypeName(colCount));
                    Integer Precision = 0;
                    Scale = 0;
                    Precision = rsMetaData.getPrecision(colCount);
                    Scale = rsMetaData.getScale(colCount);
                    if (Precision != null && Precision != 0) {
                        ddlBuilder.append("(").append(Precision);
                        if ("NUMBER".equals(rsMetaData.getColumnTypeName(colCount))) {
                            ddlBuilder.append(",").append(Scale).append(")");
                        } else {
                            ddlBuilder.append(" )");
                        }
                    }

                    String constraintSql = SQL_SELECT_TC_TABLE_SCHEMA_TC + tableName + "'; ";

                    try (PreparedStatement constraintStatement = connection.prepareStatement(constraintSql);
                         ResultSet constraintSet = constraintStatement.executeQuery()) {
                    while (constraintSet.next()) {
                        if (!constraintSet.isLast()) {
                            ddlBuilder.append(" ,\nCONSTRAINT " + constraintSet.getString(3) + " " + constraintSet.getString(4) + " ( " + constraintSet.getString(5) + " ) ");
                            ddlBuilder.append(", ");
                        } else {
                            ddlBuilder.append(" ,\nCONSTRAINT " + constraintSet.getString(3) + " " + constraintSet.getString(4) + " ( " + constraintSet.getString(5) + " ) ");
                        }
                    }
                    }

                    String tableSql = SQL_SELECT_UT_TABLE_SCHEMA_UT + tableName + "' AND UT.TABLE_SCHEMA = '"+ schemaName +"'; ";
                    try (PreparedStatement tablespaceStatement = connection.prepareStatement(tableSql);
                         ResultSet tablespaceSet = tablespaceStatement.executeQuery()) {
                    while (tablespaceSet.next()) {
                        if (tablespaceSet.isLast()) {
                            ddlBuilder.append(" ) \nPCTFREE " + tablespaceSet.getLong(4));
                            ddlBuilder.append(" \nPCTUSED " + tablespaceSet.getLong(5));
                            ddlBuilder.append(" \nINITRANS " + tablespaceSet.getLong(6));
                            ddlBuilder.append(" \nMAXTRANS " + tablespaceSet.getLong(7));
                            ddlBuilder.append(" \nSTORAGE \n( \nINITIAL " + tablespaceSet.getLong(8));
                            ddlBuilder.append(" \nNEXT " + tablespaceSet.getLong(9));
                            ddlBuilder.append(" \nMINSIZE " + tablespaceSet.getLong(10));
                            ddlBuilder.append(" \nMAXSIZE " + tablespaceSet.getLong(11));
                            ddlBuilder.append(" \n) \nTABLESPACE " + tablespaceSet.getString(3) + " ; ");
                        }
                    }
                    }
                    break;
                }

                Scale = 0;
                Integer Scale1 = 0;
                ddlBuilder.append(rsMetaData.getColumnName(i) + " ");
                ddlBuilder.append(rsMetaData.getColumnTypeName(i));
                Scale1 = rsMetaData.getPrecision(i);
                Scale = rsMetaData.getScale(i);
                if (Scale1 != null && Scale1 != 0) {
                    ddlBuilder.append("(").append(Scale1);
                    if ("NUMBER".equals(rsMetaData.getColumnTypeName(i))) {
                        ddlBuilder.append(",").append(Scale).append(") ,");
                    } else {
                        ddlBuilder.append(")").append(" ,");
                    }
                } else {
                    ddlBuilder.append(" ,");
                }

                ++i;
            }
            }
            String indexNameSql = SQL_SELECT_INDEX_SCHEMA_INDEX_NAME + schemaName + "' and table_name= '" + tableName + "'";
            try (PreparedStatement indexNameStatement = connection.prepareStatement(indexNameSql);
                 ResultSet indexNameResultSet = indexNameStatement.executeQuery()) {
            while (indexNameResultSet.next()) {
                String querySql = SQL_SELECT_COLS_INDEX_SCHEMA_COLS + tableName + "' " + SQL_COLS_INDEX_NAME + indexNameResultSet.getString("INDEX_NAME") + "' " + SQL_COLS_INDEX_SCHEMA+ schemaName +"' "+ SQL_ORDER_COLS_COLUMN_POSITION;
                try (PreparedStatement indexStatement = connection.prepareStatement(querySql);
                     ResultSet indexResultSet = indexStatement.executeQuery()) {
                ddlBuilder.append("\nCREATE ");
                if (indexNameResultSet.getString("UNIQUENESS").equals("UNIQUE")) {
                    ddlBuilder.append("UNIQUE INDEX " + getIndexName(indexNameResultSet.getString("INDEX_SCHEMA"), indexNameResultSet.getString("INDEX_NAME")) + " ");
                } else {
                    ddlBuilder.append("INDEX " + getIndexName(indexNameResultSet.getString("INDEX_SCHEMA"), indexNameResultSet.getString("INDEX_NAME")) + " ");
                }

                ddlBuilder.append(SQL_ON + indexNameResultSet.getString("TABLE_SCHEMA") + "." + indexNameResultSet.getString("TABLE_NAME") + " \n( ");

                while (indexResultSet.next()) {

                    ddlBuilder.append(indexResultSet.getString(5) + " ");
                    ddlBuilder.append(indexResultSet.getString(6) + " ");
                    ddlBuilder.append(indexResultSet.getString(7));
                    if (!indexResultSet.isLast()) {
                        ddlBuilder.append(", ");
                    } else {
                        ddlBuilder.append(" ) \nPCTFREE " + indexResultSet.getLong(9));
                        ddlBuilder.append(" \nINITRANS " + indexResultSet.getLong(10));
                        ddlBuilder.append(" \nMAXTRANS " + indexResultSet.getLong(11));
                        ddlBuilder.append(" \nSTORAGE \n( \nINITIAL " + indexResultSet.getLong(12));
                        ddlBuilder.append(" \nNEXT " + indexResultSet.getLong(13));
                        ddlBuilder.append(" \nMINSIZE " + indexResultSet.getLong(14));
                        ddlBuilder.append(" \nMAXSIZE " + indexResultSet.getLong(15));
                        ddlBuilder.append(" \n) \nTABLESPACE " + indexResultSet.getString(16) + ";\n");
                    }
                }
                }
            }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return ddlBuilder.toString();
    }

    private String getIndexName(String indexSchema, String indexName) {
        if (indexName.contains("PRIMARY_KEY_INDEX")) {
            return indexSchema + "." + indexName;
        } else {
            return indexSchema + ".\"" + indexName + "\"";
        }
    }


    @Override
    public List<Function> functions(Connection connection, String databaseName, String schemaName) {
        List<Function> functions = new ArrayList<>();
        String userName = "";
        try {
            userName = connection.getMetaData().getUserName();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        String sql = String.format(ALL_PROCEDURES_SQL, userName, schemaName, SUNDBObjectTypeEnum.FUNCTION.getObjectType());
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Function function = new Function();
                function.setDatabaseName(databaseName);
                function.setSchemaName(schemaName);
                function.setFunctionName(resultSet.getString("OBJECT_NAME"));
                functions.add(function);
            }
            return functions;
        });
    }



    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {
        String userName = "";
        try {
            userName = connection.getMetaData().getUserName();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        String sql = String.format(ALL_SOURCE_SQL, SUNDBObjectTypeEnum.FUNCTION.getObjectType(), userName, schemaName, functionName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            if (resultSet.next()) {
                function.setFunctionBody(resultSet.getString("text") + "\n");
            }
            return function;
        });

    }

    @Override
    public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
        String userName = "";
        try {
            userName = connection.getMetaData().getUserName();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        String sql = String.format(ALL_PROCEDURES_SQL, userName, schemaName, SUNDBObjectTypeEnum.PROCEDURE.getObjectType());
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            ArrayList<Procedure> procedures = new ArrayList<>();
            Procedure procedure = new Procedure();
            while (resultSet.next()) {
                procedure.setProcedureName(resultSet.getString("OBJECT_NAME"));
                procedures.add(procedure);
            }
            return procedures;
        });
    }


    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        String userName = "";
        try {
            userName = connection.getMetaData().getUserName();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        String sql = String.format(ALL_SOURCE_SQL, SUNDBObjectTypeEnum.PROCEDURE.getObjectType(), userName, schemaName, procedureName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            StringBuilder sb = new StringBuilder();
            while (resultSet.next()) {
                sb.append(resultSet.getString("TEXT") + "\n");
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
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        return Lists.newArrayList();


    }

    @Override
    public Trigger trigger(Connection connection, @NotEmpty String databaseName, String schemaName,
                           String triggerName) {


        return null;
    }



    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_SQL, schemaName, viewName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("TEXT"));
            }
            return table;
        });
    }



    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(INDEX_SQL, schemaName, tableName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap();
            while (resultSet.next()) {
                String keyName = resultSet.getString("INDEX_NAME");
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
                    index.setUnique("UNIQUE".equalsIgnoreCase(resultSet.getString("UNIQUENESS")));
                    List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
                    tableIndexColumns.add(getTableIndexColumn(resultSet));
                    index.setColumnList(tableIndexColumns);
                    if ("P".equalsIgnoreCase(resultSet.getString("CONSTRAINT_TYPE"))) {
                        index.setType(SUNDBIndexTypeEnum.PRIMARY_KEY.getName());
                    } else if (index.getUnique()) {
                        index.setType(SUNDBIndexTypeEnum.UNIQUE.getName());
                    } else if ("BITMAP".equalsIgnoreCase(resultSet.getString("INDEX_TYPE"))) {
                        index.setType(SUNDBIndexTypeEnum.BITMAP.getName());
                    } else {
                        index.setType(SUNDBIndexTypeEnum.NORMAL.getName());
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });

    }

    private TableIndexColumn getTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString("COLUMN_NAME"));
        tableIndexColumn.setOrdinalPosition(resultSet.getShort("COLUMN_POSITION"));
        String collation = resultSet.getString("DESCEND");
        if ("ASC".equalsIgnoreCase(collation)) {
            tableIndexColumn.setAscOrDesc("ASC");
        } else if ("DESC".equalsIgnoreCase(collation)) {
            tableIndexColumn.setAscOrDesc("DESC");
        }
        return tableIndexColumn;
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new SUNDBSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(SUNDBColumnTypeEnum.getTypes())
                .charsets(Lists.newArrayList())
                .collations(Lists.newArrayList())
                .indexTypes(SUNDBIndexTypeEnum.getIndexTypes())
                .defaultValues(SUNDBDefaultValueEnum.getDefaultValues())
                .build();
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
