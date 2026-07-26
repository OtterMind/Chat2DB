package ai.chat2db.plugin.mysql;

import ai.chat2db.plugin.mysql.builder.MysqlSqlBuilder;
import ai.chat2db.plugin.mysql.enums.MysqlViewAlgorithmOptionEnum;
import ai.chat2db.plugin.mysql.enums.MysqlViewCheckOptionEnum;
import ai.chat2db.plugin.mysql.enums.MysqlViewSqlSecurityOptionEnum;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import ai.chat2db.plugin.mysql.enums.type.*;
import ai.chat2db.plugin.mysql.value.MysqlValueProcessor;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.enums.plugin.ResultSetEditorTypeEnum;
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
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IResultSetFunction;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_CREATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_FROM;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_CREATE_FUNCTION;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_CREATE_PROCEDURE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_CREATE_TABLE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_INDEX_FROM;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_PROCEDURE_STATUS;
import static ai.chat2db.plugin.mysql.constant.MysqlRoutineManageConstants.FUNCTION;
import static ai.chat2db.plugin.mysql.constant.MysqlRoutineManageConstants.PROCEDURE;
import static ai.chat2db.spi.util.SortUtils.sortDatabase;

import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.*;
@Slf4j
public class MysqlMetaData extends DefaultMetaService implements IDbMetaData {

    public static final ISQLIdentifierProcessor MYSQL_IDENTIFIER_PROCESSOR = new MysqlIdentifierProcessor();

    @Override
    public List<Database> databases(Connection connection) {
        List<Database> databases = DefaultSQLExecutor.getInstance().databases(connection);
        return sortDatabase(databases, SYSTEM_DATABASES, connection);
    }


    @Override
    public List<Table> tables(Connection connection, @NotEmpty String databaseName, String schemaName, String tableName) {
        String sql = String.format(TABLES_SQL, databaseName);
        if (StringUtils.isNotBlank(tableName)) {
            sql += SQL_TABLE_NAME_EQUALS_FILTER + tableName + SQL_SINGLE_QUOTE;
        }
        Map<String, String> collationMap = getCollationMap(connection);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Table> tables = new ArrayList<>();
            while (resultSet.next()) {
                Table table = new Table();
                table.setDatabaseName(databaseName);
                table.setSchemaName(schemaName);
                table.setName(resultSet.getString(FIELD_TABLE_NAME));
                table.setEngine(resultSet.getString(FIELD_ENGINE_UPPER));
                table.setRows(resultSet.getLong(FIELD_TABLE_ROWS));
                table.setDataLength(resultSet.getLong(FIELD_DATA_LENGTH));
                table.setCreateTime(resultSet.getString(FIELD_CREATE_TIME));
                table.setUpdateTime(resultSet.getString(FIELD_UPDATE_TIME));
                String collationName = resultSet.getString(FIELD_TABLE_COLLATION);
                table.setCollate(collationName);
                table.setComment(resultSet.getString(FIELD_TABLE_COMMENT));
                table.setIncrementValue(resultSet.getLong(FIELD_AUTO_INCREMENT));
                if (StringUtils.isNotBlank(collationName)) {
                    table.setCharset(collationMap.get(collationName));
                }
                tables.add(table);
            }
            return tables;
        });
    }


    private Map<String, String> getCollationMap(Connection connection) {
        try {
            return DefaultSQLExecutor.getInstance().execute(connection, TABLE_STATUS, resultSet -> {
                Map<String, String> collationMap = new HashMap<>();
                while (resultSet.next()) {
                    String collationName = resultSet.getString(FIELD_COLLATION_NAME);
                    String characterSetName = resultSet.getString(FIELD_CHARACTER_SET_NAME);
                    if (collationName != null) {
                        collationMap.put(collationName, characterSetName);
                    }
                }
                return collationMap;
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }


    @Override
    public String tableDDL(Connection connection, @NotEmpty String databaseName, String schemaName,
                           @NotEmpty String tableName) {
        String sql = String.format(SQL_SHOW_CREATE_TABLE_TEMPLATE, mysqlQualifiedName(databaseName, tableName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                try {
                    return resultSet.getString(FIELD_CREATE_TABLE) + SQL_SEMICOLON;
                } catch (SQLException e) {
                    log.error(LOG_SYSTEM_VIEW_CREATE_TABLE_FAILED, e);
                    return resultSet.getString(FIELD_CREATE_VIEW) + SQL_SEMICOLON;
                }
            }
            return null;
        });
    }

    public static String format(String tableName) {
        return SQL_METADATA_QUOTE + tableName + SQL_METADATA_QUOTE;
    }

    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {

        String functionInfoSql = String.format(ROUTINES_SQL, FUNCTION, databaseName, functionName);
        Function function = DefaultSQLExecutor.getInstance().execute(connection, functionInfoSql, resultSet -> {
            Function f = new Function();
            f.setDatabaseName(databaseName);
            f.setSchemaName(schemaName);
            f.setFunctionName(functionName);
            if (resultSet.next()) {
                f.setSpecificName(resultSet.getString(FIELD_SPECIFIC_NAME));
                f.setRemarks(resultSet.getString(FIELD_ROUTINE_COMMENT));
            }
            return f;
        });
        String functionDDlSql = SQL_SHOW_CREATE_FUNCTION + mysqlQualifiedName(databaseName, functionName);
        DefaultSQLExecutor.getInstance().execute(connection, functionDDlSql, resultSet -> {
            if (resultSet.next()) {
                function.setFunctionBody(resultSet.getString(FIELD_CREATE_FUNCTION));
            }
        });
        return function;

    }
    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        String sql = String.format(TRIGGER_SQL_LIST, databaseName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Trigger trigger = new Trigger();
                trigger.setTriggerName(resultSet.getString(FIELD_TRIGGER_NAME));
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
        return DefaultSQLExecutor.getInstance().execute(connection, String.format(TRIGGER_SQL, format(databaseName), format(triggerName)), resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(schemaName);
            trigger.setTriggerName(triggerName);
            if (resultSet.next()) {
                trigger.setTriggerBody(resultSet.getString(FIELD_SQL_ORIGINAL_STATEMENT));
            }
            return trigger;
        });
    }

    @Override
    public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
        String sql = SQL_SHOW_PROCEDURE_STATUS;
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            ArrayList<Procedure> procedures = new ArrayList<>();
            while (resultSet.next()) {
                Procedure procedure = new Procedure();
                procedure.setProcedureName(resultSet.getString(FIELD_NAME));
                procedures.add(procedure);
            }
            return procedures;
        });
    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        String routinesSql = String.format(ROUTINES_SQL, PROCEDURE, databaseName, procedureName);
        String showCreateProcedureSql = SQL_SHOW_CREATE_PROCEDURE + mysqlQualifiedName(databaseName, procedureName);
        Procedure procedure = DefaultSQLExecutor.getInstance().execute(connection, routinesSql, resultSet -> {
            Procedure p = new Procedure();
            p.setDatabaseName(databaseName);
            p.setSchemaName(schemaName);
            p.setProcedureName(procedureName);
            if (resultSet.next()) {
                p.setSpecificName(resultSet.getString(FIELD_SPECIFIC_NAME));
                p.setRemarks(resultSet.getString(FIELD_ROUTINE_COMMENT));
            }
            return p;
        });
        DefaultSQLExecutor.getInstance().execute(connection, showCreateProcedureSql, resultSet -> {
            if (resultSet.next()) {
                procedure.setProcedureBody(resultSet.getString(FIELD_CREATE_PROCEDURE));
            }
        });
        return procedure;
    }
    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(SELECT_TABLE_COLUMNS, databaseName, tableName);
        List<TableColumn> tableColumns = new ArrayList<>();
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                TableColumn column = new TableColumn();
                column.setDatabaseName(databaseName);
                column.setTableName(tableName);
                column.setOldName(resultSet.getString(FIELD_COLUMN_NAME_UPPER));
                column.setName(resultSet.getString(FIELD_COLUMN_NAME_UPPER));
                column.setColumnType(resultSet.getString(FIELD_DATA_TYPE).toUpperCase());
                column.setDefaultValue(resultSet.getString(FIELD_COLUMN_DEFAULT));
                column.setAutoIncrement(resultSet.getString(FIELD_EXTRA).contains(SQL_AUTO_INCREMENT));
                column.setOnUpdateCurrentTimestamp(resultSet.getString(FIELD_EXTRA).contains(SQL_ON_UPDATE_CURRENT_TIMESTAMP));
                column.setComment(resultSet.getString(FIELD_COLUMN_COMMENT));
                column.setPrimaryKey(SQL_PRIMARY_KEY_FLAG.equalsIgnoreCase(resultSet.getString(FIELD_COLUMN_KEY)));
                column.setNullable(SQL_YES.equalsIgnoreCase(resultSet.getString(FIELD_IS_NULLABLE)) ? 1 : 0);
                column.setOrdinalPosition(resultSet.getInt(FIELD_ORDINAL_POSITION));
                column.setDecimalDigits(resultSet.getInt(FIELD_NUMERIC_SCALE));
                column.setCharSetName(resultSet.getString(FIELD_CHARACTER_SET_NAME));
                column.setCollationName(resultSet.getString(FIELD_COLLATION_NAME));
                setColumnSize(column, resultSet.getString(FIELD_COLUMN_TYPE));

                tableColumns.add(column);
            }
            return tableColumns;
        });
    }

    private void setColumnSize(TableColumn column, String columnType) {
        try {
            if (columnType.contains(SQL_NAME_SIZE_OPEN)) {
                String size = columnType.substring(columnType.indexOf(SQL_NAME_SIZE_OPEN) + 1, columnType.indexOf(SQL_NAME_SIZE_CLOSE));
                if (SQL_SET_TYPE.equalsIgnoreCase(column.getColumnType()) || SQL_ENUM_TYPE.equalsIgnoreCase(column.getColumnType())) {
                    column.setValue(size);
                } else {
                    if (size.contains(SQL_TYPE_SIZE_SEPARATOR)) {
                        String[] sizes = size.split(SQL_TYPE_SIZE_SEPARATOR);
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
        String quoteViewName = MYSQL_IDENTIFIER_PROCESSOR.quoteIdentifier(viewName);
        String sql = String.format(VIEW_DDL_SQL, quoteViewName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString(FIELD_CREATE_VIEW));
            }
            return table;
        });
    }


    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        StringBuilder queryBuf = new StringBuilder(SQL_SHOW_INDEX_FROM);
        queryBuf.append(SQL_METADATA_QUOTE).append(tableName).append(SQL_METADATA_QUOTE);
        queryBuf.append(SQL_FROM);
        queryBuf.append(SQL_METADATA_QUOTE).append(databaseName).append(SQL_METADATA_QUOTE);
        return DefaultSQLExecutor.getInstance().execute(connection, queryBuf.toString(), resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap();
            while (resultSet.next()) {
                String keyName = resultSet.getString(FIELD_KEY_NAME);
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
                    index.setUnique(!resultSet.getBoolean(FIELD_NON_UNIQUE));
                    index.setType(resultSet.getString(FIELD_INDEX_TYPE));
                    index.setMethod(resultSet.getString(FIELD_INDEX_TYPE));

                    try {
                        index.setComment(resultSet.getString(FIELD_INDEX_COMMENT));
                    } catch (SQLException e) {
                        log.error(LOG_INDEX_COMMENT_FAILED, keyName, e);
                        index.setComment(resultSet.getString(FIELD_INDEX_COMMENT_FALLBACK));
                    }
                    List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
                    tableIndexColumns.add(getTableIndexColumn(resultSet));
                    index.setColumnList(tableIndexColumns);
                    if (SQL_PRIMARY_INDEX_NAME.equalsIgnoreCase(keyName)) {
                        index.setType(MysqlIndexTypeEnum.PRIMARY_KEY.getName());
                    } else if (index.getUnique()) {
                        index.setType(MysqlIndexTypeEnum.UNIQUE.getName());
                    } else if (SQL_SPATIAL_INDEX_TYPE.equalsIgnoreCase(index.getType())) {
                        index.setType(MysqlIndexTypeEnum.SPATIAL.getName());
                    } else if (SQL_FULLTEXT_INDEX_TYPE.equalsIgnoreCase(index.getType())) {
                        index.setType(MysqlIndexTypeEnum.FULLTEXT.getName());
                    } else {
                        index.setType(MysqlIndexTypeEnum.NORMAL.getName());
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });

    }

    private TableIndexColumn getTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString(FIELD_COLUMN_NAME));
        tableIndexColumn.setOrdinalPosition(resultSet.getShort(FIELD_SEQ_IN_INDEX));
        tableIndexColumn.setCollation(resultSet.getString(FIELD_COLLATION));
        tableIndexColumn.setCardinality(resultSet.getLong(FIELD_CARDINALITY));
        tableIndexColumn.setSubPart(resultSet.getLong(FIELD_SUB_PART));
        String collation = resultSet.getString(FIELD_COLLATION);
        if (INDEX_COLLATION_ASC.equalsIgnoreCase(collation)) {
            tableIndexColumn.setAscOrDesc(INDEX_ASC);
        } else if (INDEX_COLLATION_DESC.equalsIgnoreCase(collation)) {
            tableIndexColumn.setAscOrDesc(INDEX_DESC);
        }
        return tableIndexColumn;
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new MysqlSqlBuilder();
    }

    private String mysqlQualifiedName(String databaseName, String objectName) {
        return Arrays.stream(new String[]{databaseName, objectName})
                .filter(StringUtils::isNotBlank)
                .map(MysqlMetaData::format)
                .collect(Collectors.joining(SQL_DOT));
    }

    @Override
    public String resolveResultSetEditorType(String typeName, Integer type) {
        String normalizedTypeName = StringUtils.substringBefore(StringUtils.upperCase(StringUtils.trimToEmpty(typeName)), "(");
        ResultSetEditorTypeEnum editorType = RESULT_SET_EDITOR_TYPE_BY_TYPE_NAME.get(normalizedTypeName);
        if (editorType != null) {
            return editorType.getCode();
        }
        if (type == null) {
            return ResultSetEditorTypeEnum.TEXT.getCode();
        }
        return RESULT_SET_EDITOR_TYPE_BY_JDBC_TYPE.getOrDefault(type, ResultSetEditorTypeEnum.TEXT).getCode();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(MysqlColumnTypeEnum.getTypes())
                .charsets(getCharsets())
                .collations(getCollations())
                .indexTypes(MysqlIndexTypeEnum.getIndexTypes())
                .defaultValues(MysqlDefaultValueEnum.getDefaultValues())
                .engineTypes(getEngineTypes())
                .build();
    }

    private List<Charset> getCharsets() {
        return queryTableMetaOptions(SHOW_CHARACTER_SET_SQL, resultSet -> {
            List<Charset> list = new ArrayList<>();
            while (resultSet.next()) {
                list.add(new Charset(resultSet.getString(FIELD_CHARSET), resultSet.getString(FIELD_DEFAULT_COLLATION)));
            }
            return list;
        }, MysqlCharsetEnum.getCharsets(), "character sets");
    }

    private List<Collation> getCollations() {
        return queryTableMetaOptions(SHOW_COLLATION_SQL, resultSet -> {
            List<Collation> list = new ArrayList<>();
            while (resultSet.next()) {
                list.add(new Collation(resultSet.getString(FIELD_COLLATION)));
            }
            return list;
        }, MysqlCollationEnum.getCollations(), "collations");
    }

    private List<EngineType> getEngineTypes() {
        return queryTableMetaOptions(SHOW_ENGINES_SQL, resultSet -> {
            List<EngineType> list = new ArrayList<>();
            while (resultSet.next()) {
                String support = resultSet.getString(FIELD_SUPPORT);
                if (ENGINE_SUPPORT_YES.equalsIgnoreCase(support) || ENGINE_SUPPORT_DEFAULT.equalsIgnoreCase(support)) {
                    list.add(new EngineType(resultSet.getString(FIELD_ENGINE), false, false, false, false, false, false, false, false));
                }
            }
            return list;
        }, MysqlEngineTypeEnum.getEngineTypes(), "engines");
    }

    private <T> List<T> queryTableMetaOptions(String sql, IResultSetFunction<List<T>> function,
                                             List<T> fallback, String optionName) {
        try {
            Connection connection = Chat2DBContext.getConnection();
            if (connection == null) {
                return fallback;
            }
            List<T> options = DefaultSQLExecutor.getInstance().execute(connection, sql, function);
            if (options != null && !options.isEmpty()) {
                return options;
            }
        } catch (Exception e) {
            log.warn("query mysql {} failed, fallback to static list", optionName, e);
        }
        return fallback;
    }

    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(StringUtils::isNotBlank)
                .map(name -> SQL_METADATA_QUOTE + name + SQL_METADATA_QUOTE)
                .collect(Collectors.joining(SQL_DOT));
    }

    @Override
    public IValueProcessor getValueProcessor() {
        return new MysqlValueProcessor();
    }


    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return MYSQL_IDENTIFIER_PROCESSOR;
    }

    @Override
    public List<String> getSystemDatabases() {
        return SYSTEM_DATABASES;
    }

    @Override
    public ModifyViewConfiguration viewMeta(String databaseName, String schemaName) {
        ModifyViewConfiguration configuration = new ModifyViewConfiguration();
        ArrayList<FormConfig> formConfigs = new ArrayList<>(6);
        formConfigs.add(MysqlViewAlgorithmOptionEnum.getFormConfig());
        formConfigs.add(MysqlViewCheckOptionEnum.getFormConfig());
        formConfigs.add(MysqlViewSqlSecurityOptionEnum.getFormConfig());
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage(I18N_MODIFY_VIEW_CONFIG_NAME), FORM_FIELD_VIEW_NAME));
        formConfigs.add(FormConfig.getInputForm(I18nUtils.getMessage(I18N_MODIFY_VIEW_CONFIG_DEFINER), FORM_FIELD_DEFINER));
        formConfigs.add(FormConfig.getCheckBox(FORM_LABEL_USE_OR_REPLACE, FORM_FIELD_USE_OR_REPLACE));
        configuration.setConfigurations(formConfigs);
        String sql = SQL_SELECT_PREVIEW_TABLE;
        StringBuilder sqlBuilder = new StringBuilder(100);
        sqlBuilder.append(SQL_CREATE).append(SQL_VIEW_KEYWORD);
        if (StringUtils.isNotBlank(databaseName)) {
            sqlBuilder.append(SQL_METADATA_QUOTE).append(databaseName).append(SQL_METADATA_QUOTE).append(SQL_DOT);
        }
        sqlBuilder.append(SQL_METADATA_QUOTE).append(SQL_UNDEFINED).append(SQL_METADATA_QUOTE);
        sqlBuilder.append(SQL_AS).append(sql).append(SQL_SEMICOLON);
        configuration.setPreviewSql(sqlBuilder.toString());
        configuration.setSql(sql);
        return configuration;
    }


    @Override
    public Boolean supportCrossDatabase() {
        return Boolean.TRUE;
    }
}
