package ai.chat2db.plugin.oracle;

import ai.chat2db.plugin.oracle.builder.OracleSqlBuilder;
import ai.chat2db.plugin.oracle.enums.*;
import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;
import ai.chat2db.plugin.oracle.enums.type.OracleColumnTypeEnum;
import ai.chat2db.plugin.oracle.enums.type.OracleDefaultValueEnum;
import ai.chat2db.plugin.oracle.enums.type.OracleIndexTypeEnum;
import ai.chat2db.plugin.oracle.value.OracleValueProcessor;
import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
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
import ai.chat2db.spi.util.SqlUtils;
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.oracle.constant.OracleMetaDataConstants.*;
@Slf4j
public class OracleMetaData extends DefaultMetaService implements IDbMetaData {


    public static final ISQLIdentifierProcessor ORACLE_SQL_IDENTIFIER_PROCESSOR = new OracleIdentifierProcessor();

    @Override
    public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
        String sql = String.format(PROCEDURE_LIST_DDL, schemaName);
        ArrayList<Procedure> procedures = new ArrayList<>();
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Procedure procedure = new Procedure();
                procedure.setProcedureName(resultSet.getString("object_name"));
                procedures.add(procedure);
            }
        });
        return procedures;
    }

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = DefaultSQLExecutor.getInstance().schemas(connection, databaseName, null);
        return SortUtils.sortSchema(schemas, SYSTEM_SCHEMAS);
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(TABLE_DDL_SQL, tableName, schemaName);
        String tableCommentSql = String.format(TABLE_COMMENT_SQL, schemaName, tableName);
        String tableColumnCommentSql = String.format(TABLE_COLUMN_COMMENT_SQL, schemaName, tableName);
        String tableIndexSql = String.format(TABLE_INDEX_DDL_SQL, schemaName, tableName);
        String PUIndexSql = String.format(PU_INDEX_NAME_SQL, schemaName, tableName);
        StringBuilder ddlBuilder = new StringBuilder();
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            try {
                if (resultSet.next()) {
                    ddlBuilder.append(resultSet.getString("sql")).append(";");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        DefaultSQLExecutor.getInstance().execute(connection, tableCommentSql, resultSet -> {
            if (resultSet.next()) {
                String tableComment = resultSet.getString("comments");
                if (StringUtils.isNotBlank(tableComment)) {
                    ddlBuilder.append("\nCOMMENT ON TABLE ").append(SqlUtils.quoteObjectName(tableName)).append(" IS ")
                            .append(EasyStringUtils.escapeAndQuoteString(tableComment)).append(";");
                }
            }
        });
        DefaultSQLExecutor.getInstance().execute(connection, tableColumnCommentSql, resultSet -> {
            while (resultSet.next()) {
                String columnName = resultSet.getString("column_name");
                String columnComment = resultSet.getString("comments");
                if (StringUtils.isNotBlank(columnComment)) {
                    ddlBuilder.append("\nCOMMENT ON COLUMN ")
                            .append(SqlUtils.quoteObjectName(tableName)).append(".")
                            .append(SqlUtils.quoteObjectName(columnName)).append(" IS ")
                            .append(EasyStringUtils.escapeAndQuoteString(columnComment)).append(";");
                }
            }
        });
        List<String> indexNames = DefaultSQLExecutor.getInstance().execute(connection, PUIndexSql, resultSet -> {
            List<String> PUIndexNames = new ArrayList<>();
            while (resultSet.next()) {
                String indexName = resultSet.getString("index_name");
                if (StringUtils.isNotBlank(indexName)) {
                    PUIndexNames.add(indexName);
                }
            }
            return PUIndexNames;
        });
        DefaultSQLExecutor.getInstance().execute(connection, tableIndexSql, resultSet -> {
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                if (CollectionUtils.isNotEmpty(indexNames) && indexNames.contains(indexName)) {
                    continue;
                }
                String ddl = resultSet.getString("ddl");
                if (StringUtils.isNotBlank(ddl)) {
                    ddlBuilder.append("\n").append(ddl).append(";");
                }
            }
        });
        return ddlBuilder.toString();

    }



    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(SELECT_TABLE_SQL, schemaName);
        if (StringUtils.isNotBlank(tableName)) {
            sql = sql + " and A.TABLE_NAME = '" + tableName.replace("'", "''") + "'";
        }
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Table> tables = new ArrayList<>();
            while (resultSet.next()) {
                Table table = new Table();
                table.setDatabaseName(databaseName);
                table.setSchemaName(schemaName);
                table.setName(resultSet.getString("TABLE_NAME"));
                table.setComment(resultSet.getString("COMMENTS"));
                tables.add(table);
            }
            return tables;
        });
    }



    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableColumn> tableColumns = super.columns(connection, databaseName, schemaName, tableName);
        if (CollectionUtils.isNotEmpty(tableColumns)) {
            Map<String, TableColumn> tableColumnMap = getTableColumns(connection, databaseName, schemaName, tableName);
            for (TableColumn tableColumn : tableColumns) {
                tableColumn.setColumnType(SqlUtils.removeDigits(tableColumn.getColumnType()));
                TableColumn column = tableColumnMap.get(tableColumn.getName());
                if (column != null) {
                    tableColumn.setUnit(column.getUnit());
                    tableColumn.setComment(column.getComment());
                    tableColumn.setDefaultValue(column.getDefaultValue());
                    tableColumn.setOrdinalPosition(column.getOrdinalPosition());
                    tableColumn.setNullable(column.getNullable());
                }
            }
        }
        return tableColumns;
    }

    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName, String columnName) {
        List<TableColumn> tableColumns = super.columns(connection, databaseName, schemaName, tableName, columnName);
        if (CollectionUtils.isNotEmpty(tableColumns)) {
            Map<String, TableColumn> tableColumnMap = getTableColumns(connection, databaseName, schemaName, tableName);
            for (TableColumn tableColumn : tableColumns) {
                tableColumn.setColumnType(SqlUtils.removeDigits(tableColumn.getColumnType()));
                TableColumn column = tableColumnMap.get(tableColumn.getName());
                if (column != null) {
                    tableColumn.setUnit(column.getUnit());
                    tableColumn.setComment(column.getComment());
                    tableColumn.setDefaultValue(column.getDefaultValue());
                    tableColumn.setOrdinalPosition(column.getOrdinalPosition());
                    tableColumn.setNullable(column.getNullable());
                }
            }
        }
        return tableColumns;
    }

    private Map<String, TableColumn> getTableColumns(Connection connection, String databaseName, String schemaName, String tableName) {
        Map<String, TableColumn> tableColumns = new HashMap<>();
        String sql = String.format(SELECT_TAB_COLS, schemaName, tableName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                TableColumn tableColumn = new TableColumn();
                tableColumn.setTableName(tableName);
                tableColumn.setSchemaName(schemaName);
                try {
                    Reader reader = resultSet.getCharacterStream("DATA_DEFAULT");
                    if (reader != null) {
                        StringBuilder sb = new StringBuilder();
                        int charValue;
                        while ((charValue = reader.read()) != -1) {
                            sb.append((char) charValue);
                        }
                        tableColumn.setDefaultValue(sb.toString());
                    }
                } catch (Exception e) {
                    log.error("getDefaultValue error", e);
                }
                tableColumn.setName(resultSet.getString("COLUMN_NAME"));
                String dataType = resultSet.getString("DATA_TYPE");
                if (dataType.contains("(")) {
                    dataType = dataType.substring(0, dataType.indexOf("(")).trim();
                }
                tableColumn.setColumnType(dataType);
                Integer dataPrecision = resultSet.getInt("DATA_PRECISION");
                if (resultSet.getString("DATA_PRECISION") != null) {
                    tableColumn.setColumnSize(dataPrecision);
                } else {
                    tableColumn.setColumnSize(resultSet.getInt("DATA_LENGTH"));
                }


                tableColumn.setComment(resultSet.getString("COMMENTS"));
                tableColumn.setNullable("Y".equalsIgnoreCase(resultSet.getString("NULLABLE")) ? 1 : 0);
                tableColumn.setOrdinalPosition(resultSet.getInt("COLUMN_ID"));
                tableColumn.setDecimalDigits(resultSet.getInt("DATA_SCALE"));
                String charUsed = resultSet.getString("CHAR_USED");
                if ("B".equalsIgnoreCase(charUsed)) {
                    tableColumn.setUnit("BYTE");
                } else if ("C".equalsIgnoreCase(charUsed)) {
                    tableColumn.setUnit("CHAR");
                }
                tableColumns.put(tableColumn.getName(), tableColumn);
            }
            return tableColumns;
        });

    }



    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {
        String sql = String.format(ROUTINES_SQL, "FUNCTION", schemaName, functionName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            StringBuilder bodyBuilder = new StringBuilder(SQL_CREATE_REPLACE);
            while (resultSet.next()) {
                appendRoutineSourceText(bodyBuilder, resultSet.getString("TEXT"));
            }
            String functionBody = bodyBuilder.toString().trim();
            if (!functionBody.endsWith("/")) {
                functionBody += "\n/";
            }
            function.setFunctionBody(functionBody);
            return function;

        });

    }








    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        String pkSql = String.format(SELECT_PK_SQL, schemaName, tableName);
        Set<String> pkSet = new HashSet<>();
        DefaultSQLExecutor.getInstance().execute(connection, pkSql, resultSet -> {
                    while (resultSet.next()) {
                        pkSet.add(resultSet.getString("CONSTRAINT_NAME"));
                    }
                    return null;
                }
        );

        String sql = String.format(SELECT_TABLE_INDEX, schemaName, tableName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap();
            while (resultSet.next()) {
                String keyName = resultSet.getString("Key_name");
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
                    index.setUnique("unique".equalsIgnoreCase(resultSet.getString("Unique_name")));
                    index.setType(resultSet.getString("Index_type"));
                    List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
                    tableIndexColumns.add(getTableIndexColumn(resultSet));
                    index.setColumnList(tableIndexColumns);
                    if (index.getUnique()) {
                        index.setType(OracleIndexTypeEnum.UNIQUE.getName());
                    } else if ("NORMAL".equalsIgnoreCase(index.getType())) {
                        index.setType(OracleIndexTypeEnum.NORMAL.getName());
                    } else if ("BITMAP".equalsIgnoreCase(index.getType())) {
                        index.setType(OracleIndexTypeEnum.BITMAP.getName());
                    } else if (StringUtils.isNotBlank(index.getType()) && index.getType().toUpperCase().contains("NORMAL")) {
                        index.setType(OracleIndexTypeEnum.NORMAL.getName());
                    }
                    if (pkSet.contains(keyName)) {
                        index.setType(OracleIndexTypeEnum.PRIMARY_KEY.getName());
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });

    }

    private TableIndexColumn getTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString("Column_name"));
        String expression = resultSet.getString("COLUMN_EXPRESSION");
        if (!StringUtils.isBlank(expression)) {
            tableIndexColumn.setColumnName(expression.replace("\"", ""));
        }
        tableIndexColumn.setOrdinalPosition(resultSet.getShort("Seq_in_index"));
        tableIndexColumn.setCollation(resultSet.getString("Collation"));
        tableIndexColumn.setAscOrDesc(resultSet.getString("Collation"));
        return tableIndexColumn;
    }

    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        return DefaultSQLExecutor.getInstance().execute(connection, String.format(TRIGGER_SQL_LIST, schemaName),
                resultSet -> {
                    while (resultSet.next()) {
                        String triggerName = resultSet.getString("TRIGGER_NAME");
                        Trigger trigger = new Trigger();
                        trigger.setTriggerName(triggerName == null ? "" : triggerName.trim());
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
        String sql = String.format(TRIGGER_DDL_SQL, triggerName, schemaName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(schemaName);
            trigger.setTriggerName(triggerName);
            while (resultSet.next()) {
                trigger.setTriggerBody(resultSet.getString("ddl"));
            }
            return trigger;
        });
    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        String sql = String.format(ROUTINES_SQL, "PROCEDURE", schemaName, procedureName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Procedure procedure = new Procedure();
            procedure.setDatabaseName(databaseName);
            procedure.setSchemaName(schemaName);
            procedure.setProcedureName(procedureName);
            StringBuilder bodyBuilder = new StringBuilder(SQL_CREATE_REPLACE);
            while (resultSet.next()) {
                appendRoutineSourceText(bodyBuilder, resultSet.getString("TEXT"));
            }
            String procedureBody = bodyBuilder.toString().trim();
            if (!procedureBody.endsWith("/")) {
                procedureBody += "\n/";
            }
            procedure.setProcedureBody(procedureBody);
            return procedure;
        });
    }

    static void appendRoutineSourceText(StringBuilder bodyBuilder, String sourceText) {
        if (sourceText == null) {
            return;
        }
        bodyBuilder.append(sourceText);
        if (!sourceText.endsWith("\n") && !sourceText.endsWith("\r")) {
            bodyBuilder.append("\n");
        }
    }




    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_DDL_SQL, schemaName, viewName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl("CREATE OR REPLACE VIEW " + viewName + " AS " + resultSet.getString("TEXT"));
            }
            return table;
        });
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new OracleSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(OracleColumnTypeEnum.getTypes())
                .charsets(Lists.newArrayList())
                .collations(Lists.newArrayList())
                .indexTypes(OracleIndexTypeEnum.getIndexTypes())
                .defaultValues(OracleDefaultValueEnum.getDefaultValues())
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


    @Override
    public IValueProcessor getValueProcessor() {
        return new OracleValueProcessor();
    }

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return ORACLE_SQL_IDENTIFIER_PROCESSOR;
    }

    @Override
    public Table getTable(List<Table> tables, String tableName) {
        if (StringUtils.isBlank(tableName)) {
            return null;
        }
        HashSet<Table> tableSet = new HashSet<>();
        for (Table table : tables) {
            if (StringUtils.equalsIgnoreCase(table.getName(), tableName)) {
                tableSet.add(table);
            }
        }
        int size = tableSet.size();
        if (size == 1) {
            return tableSet.iterator().next();
        } else if (size > 1) {
            for (Table table : tableSet) {
                if (table.getName().equals(tableName)) {
                    return table;
                }
            }
        }
        return null;

    }

    @Override
    public ModifyViewConfiguration viewMeta(String databaseName, String schemaName) {
        ModifyViewConfiguration configuration = new ModifyViewConfiguration();
        ArrayList<FormConfig> formConfigs = new ArrayList<>(11);
        formConfigs.add(OracleViewEditOptionEnum.getFormConfig());
        formConfigs.add(OracleViewShareOptionEnum.getFormConfig());
        formConfigs.add(OracleViewContainerOptionEnum.getFormConfig());
        formConfigs.add(OracleViewSqlSecurityOptionEnum.getFormConfig());
        formConfigs.add(OracleViewSubqueryRestrictionOptionEnum.getFormConfig());
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage("gui.modify.view.config.name"), "viewName"));
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage("gui.modify.view.config.collation"), "collationClause"));
        formConfigs.add(FormConfig.getCheckBox("use or replace", "useOrReplace"));
        formConfigs.add(FormConfig.getCheckBox("use force", "useForce"));
        formConfigs.add(FormConfig.getCheckBox("use if not exist", "useIfNotExist"));
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage("gui.modify.view.config.constraintName"), "subqueryConstraintName", "subqueryRestrictionClause===1"));
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage("gui.modify.view.config.comment"), "comment"));
        configuration.setConfigurations(formConfigs);
        String sql = "select * from table_name";
        StringBuilder sqlBuilder = new StringBuilder(100);
        sqlBuilder.append(SQL_CREATE).append("view ");
        if (StringUtils.isNotBlank(schemaName)) {
            sqlBuilder.append("\"").append(schemaName).append("\"").append(".");
        }
        sqlBuilder.append("\"").append("undefined").append("\"");
        sqlBuilder.append(" AS \n").append(sql).append(";");
        configuration.setPreviewSql(sqlBuilder.toString());
        configuration.setSql(sql);
        return configuration;
    }

    @Override
    public Boolean supportCrossSchema() {
        return Boolean.TRUE;
    }
}
