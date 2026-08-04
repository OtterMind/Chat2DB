package ai.chat2db.plugin.oscar;

import ai.chat2db.plugin.oscar.builder.OscarSqlBuilder;
import ai.chat2db.plugin.oscar.constant.OscarConstants;
import ai.chat2db.plugin.oscar.enums.type.OscarColumnTypeEnum;
import ai.chat2db.plugin.oscar.enums.type.OscarDefaultValueEnum;
import ai.chat2db.plugin.oscar.enums.type.OscarIndexTypeEnum;
import ai.chat2db.plugin.oscar.enums.type.OscarObjectTypeEnum;
import ai.chat2db.plugin.oscar.enums.type.OscarTypeAliasEnum;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.community.domain.api.model.form.FormConfig;
import ai.chat2db.community.domain.api.model.metadata.Function;
import ai.chat2db.community.domain.api.model.view.ModifyViewConfiguration;
import ai.chat2db.community.domain.api.model.metadata.PrimaryKey;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.metadata.TableMeta;
import ai.chat2db.community.domain.api.model.metadata.Trigger;
import ai.chat2db.spi.DefaultSQLExecutor;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.oscar.constant.OscarMetaDataConstants.*;
public class OscarMetaData extends OscarBaseMetaData {


    private static final String[] TABLE_TYPES = {TABLE_TYPE};
    private static final String[] SYSTEM_SCHEMA_TABLE_TYPES = {TABLE_TYPE, SYSTEM_TABLE_TYPE};
    private static final String[] VIEW_TYPES = {"VIEW"};
    private static final Set<String> TIMESTAMP_DATA_TYPES = Set.of(
            "TIMESTAMP",
            "TIMESTAMP WITH LOCAL TIME ZONE",
            "TIMESTAMP WITH TIME ZONE"
    );


    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        String owner = normalizeSchema(schemaName);
        String normalizedTableName = StringUtils.isBlank(tableName) ? null : normalizeIdentifier(tableName);
        return DefaultSQLExecutor.getInstance().tables(connection, normalizeCatalog(databaseName), owner, normalizedTableName,
                tableTypes(owner));
    }

    @Override
    public List<String> tableNames(Connection connection, String databaseName, String schemaName, String tableName) {
        String owner = normalizeSchema(schemaName);
        String normalizedTableName = StringUtils.isBlank(tableName) ? null : normalizeIdentifier(tableName);
        return DefaultSQLExecutor.getInstance().tableNames(connection, normalizeCatalog(databaseName), owner,
                normalizedTableName, tableTypes(owner));
    }

    @Override
    public List<Table> views(Connection connection, String databaseName, String schemaName) {
        return views(connection, databaseName, schemaName, null);
    }

    @Override
    public List<Table> views(Connection connection, String databaseName, String schemaName, String viewName) {
        String owner = normalizeSchema(schemaName);
        String normalizedViewName = StringUtils.isBlank(viewName) ? null : normalizeIdentifier(viewName);
        return DefaultSQLExecutor.getInstance().tables(connection, normalizeCatalog(databaseName), owner, normalizedViewName,
                VIEW_TYPES);
    }

    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String owner = normalizeSchema(schemaName);
        String sql = String.format(OscarConstants.VIEW_DDL_SQL, getSQLIdentifierProcessor().escapeString(owner),
                getSQLIdentifierProcessor().escapeString(normalizeIdentifier(viewName)));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(owner);
            table.setName(normalizeIdentifier(viewName));
            table.setType(OscarObjectTypeEnum.VIEW.getCode());
            if (resultSet.next()) {
                table.setDdl(SQLConstants.CREATE_OR_REPLACE_VIEW_SQL_PREFIX
                        + qualifiedName(owner, resultSet.getString(OscarConstants.CATALOG_VIEW_NAME))
                        + SQLConstants.SQL_AS
                        + resultSet.getString(OscarConstants.CATALOG_SOURCE_TEXT));
            }
            return table;
        });
    }

    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        return getTableColumns(connection, databaseName, schemaName, tableName);
    }

    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName,
                                     String columnName) {
        return getTableColumns(connection, databaseName, schemaName, tableName, columnName);
    }

    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        String owner = normalizeSchema(schemaName);
        String normalizedTableName = normalizeIdentifier(tableName);
        String catalog = normalizeCatalog(databaseName);
        List<TableIndex> indexes = DefaultSQLExecutor.getInstance().indexes(connection, catalog, owner, normalizedTableName);
        if (CollectionUtils.isEmpty(indexes)) {
            return indexes;
        }
        List<PrimaryKey> primaryKeys = DefaultSQLExecutor.getInstance().getPrimaryKeys(connection, catalog, owner,
                normalizedTableName);
        Set<String> primaryKeyNames = primaryKeys.stream()
                .map(PrimaryKey::getPrimaryKeyName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        Set<String> primaryKeyColumns = primaryKeys.stream()
                .map(PrimaryKey::getColumnName)
                .filter(StringUtils::isNotBlank)
                .map(this::normalizeIdentifier)
                .collect(Collectors.toSet());
        return indexes.stream()
                .peek(index -> normalizeIndex(index, databaseName, owner, normalizedTableName,
                        primaryKeyNames, primaryKeyColumns))
                .collect(Collectors.toList());
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String owner = normalizeSchema(schemaName);
        String normalizedTableName = normalizeIdentifier(tableName);
        List<Table> tables = tables(connection, databaseName, owner, normalizedTableName);
        if (CollectionUtils.isEmpty(tables)) {
            return SQLConstants.EMPTY;
        }
        Table table = tables.get(0);
        table.setColumnList(columns(connection, databaseName, owner, normalizedTableName));
        table.setIndexList(indexes(connection, databaseName, owner, normalizedTableName));
        return getSqlBuilder().ddl().table().buildCreateTable(table, null);
    }

    @Override
    public List<Function> functions(Connection connection, String databaseName, String schemaName) {
        String owner = normalizeSchema(schemaName);
        List<Function> functions = DefaultSQLExecutor.getInstance().functions(connection, normalizeCatalog(databaseName),
                owner);
        if (CollectionUtils.isEmpty(functions)) {
            return functions;
        }
        return functions.stream()
                .filter(function -> StringUtils.isNotBlank(function.getFunctionName()))
                .peek(function -> {
                    function.setDatabaseName(databaseName);
                    function.setSchemaName(owner);
                    function.setFunctionName(normalizeIdentifier(function.getFunctionName()));
                })
                .collect(Collectors.toList());
    }

    @Override
    public Function function(Connection connection, String databaseName, String schemaName, String functionName) {
        String owner = normalizeSchema(schemaName);
        String normalizedFunctionName = normalizeIdentifier(functionName);
        String sql = String.format(OscarConstants.ROUTINES_SQL, getSQLIdentifierProcessor().escapeString(owner),
                getSQLIdentifierProcessor().escapeString(normalizedFunctionName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(owner);
            function.setFunctionName(normalizedFunctionName);
            String body = readSource(resultSet);
            function.setFunctionBody(SQLConstants.CREATE_OR_REPLACE_FUNCTION_SQL_PREFIX
                    + qualifiedName(owner, normalizedFunctionName)
                    + body);
            return function;
        });
    }

    @Override
    public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
        String owner = normalizeSchema(schemaName);
        List<Procedure> procedures = DefaultSQLExecutor.getInstance().procedures(connection, normalizeCatalog(databaseName),
                owner);
        if (CollectionUtils.isEmpty(procedures)) {
            return procedures;
        }
        return procedures.stream()
                .filter(procedure -> StringUtils.isNotBlank(procedure.getProcedureName()))
                .peek(procedure -> {
                    procedure.setDatabaseName(databaseName);
                    procedure.setSchemaName(owner);
                    procedure.setProcedureName(normalizeIdentifier(procedure.getProcedureName()));
                })
                .collect(Collectors.toList());
    }

    @Override
    public Procedure procedure(Connection connection, String databaseName, String schemaName, String procedureName) {
        String owner = normalizeSchema(schemaName);
        String normalizedProcedureName = normalizeIdentifier(procedureName);
        String sql = String.format(OscarConstants.ROUTINES_SQL, getSQLIdentifierProcessor().escapeString(owner),
                getSQLIdentifierProcessor().escapeString(normalizedProcedureName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Procedure procedure = new Procedure();
            procedure.setDatabaseName(databaseName);
            procedure.setSchemaName(owner);
            procedure.setProcedureName(normalizedProcedureName);
            String body = readSource(resultSet);
            procedure.setProcedureBody(SQLConstants.CREATE_OR_REPLACE_PROCEDURE_SQL_PREFIX
                    + qualifiedName(owner, normalizedProcedureName)
                    + body);
            return procedure;
        });
    }

    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        String owner = normalizeSchema(schemaName);
        String sql = String.format(OscarConstants.TRIGGER_LIST_SQL, getSQLIdentifierProcessor().escapeString(owner));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Trigger> triggers = new ArrayList<>();
            while (resultSet.next()) {
                Trigger trigger = new Trigger();
                trigger.setDatabaseName(databaseName);
                trigger.setSchemaName(owner);
                trigger.setTriggerName(resultSet.getString(OscarConstants.CATALOG_TRIGGER_NAME));
                triggers.add(trigger);
            }
            return triggers;
        });
    }

    @Override
    public Trigger trigger(Connection connection, String databaseName, String schemaName, String triggerName) {
        String owner = normalizeSchema(schemaName);
        String normalizedTriggerName = normalizeIdentifier(triggerName);
        String sql = String.format(OscarConstants.TRIGGER_DETAIL_SQL, getSQLIdentifierProcessor().escapeString(owner),
                getSQLIdentifierProcessor().escapeString(normalizedTriggerName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(owner);
            trigger.setTriggerName(normalizedTriggerName);
            if (resultSet.next()) {
                trigger.setEventManipulation(resultSet.getString(OscarConstants.CATALOG_TRIGGERING_EVENT));
                trigger.setTriggerBody(buildTriggerDdl(resultSet));
            }
            return trigger;
        });
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new OscarSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(OscarColumnTypeEnum.getTypes())
                .charsets(Lists.newArrayList())
                .collations(Lists.newArrayList())
                .indexTypes(OscarIndexTypeEnum.getIndexTypes())
                .defaultValues(OscarDefaultValueEnum.getDefaultValues())
                .build();
    }

    @Override
    public ModifyViewConfiguration viewMeta(String databaseName, String schemaName) {
        ModifyViewConfiguration configuration = new ModifyViewConfiguration();
        ArrayList<FormConfig> formConfigs = new ArrayList<>(3);
        formConfigs.add(FormConfig.getInputForm(OscarConstants.VIEW_NAME_FORM_LABEL,
                OscarConstants.VIEW_NAME_FORM_KEY));
        formConfigs.add(FormConfig.getCheckBox(OscarConstants.USE_OR_REPLACE_FORM_LABEL,
                OscarConstants.USE_OR_REPLACE_FORM_KEY));
        formConfigs.add(FormConfig.getInputForm(OscarConstants.COMMENT_FORM_LABEL,
                OscarConstants.COMMENT_FORM_KEY));
        configuration.setConfigurations(formConfigs);
        configuration.setSql(OscarConstants.VIEW_PREVIEW_BODY);
        configuration.setPreviewSql(SQLConstants.CREATE_OR_REPLACE_VIEW_SQL_PREFIX
                + qualifiedName(schemaName, OscarConstants.UNDEFINED_OBJECT_NAME)
                + SQLConstants.SQL_AS_LINE_SEPARATOR + OscarConstants.VIEW_PREVIEW_BODY + SQLConstants.SEMICOLON);
        return configuration;
    }

    private List<TableColumn> getTableColumns(Connection connection, String databaseName, String schemaName,
                                              String tableName) {
        return getTableColumns(connection, databaseName, schemaName, tableName, null);
    }

    private List<TableColumn> getTableColumns(Connection connection, String databaseName, String schemaName,
                                              String tableName, String columnName) {
        String owner = normalizeSchema(schemaName);
        String normalizedTableName = normalizeIdentifier(tableName);
        String normalizedColumnName = StringUtils.isBlank(columnName) ? null : normalizeIdentifier(columnName);
        List<TableColumn> columns = DefaultSQLExecutor.getInstance().columns(connection, normalizeCatalog(databaseName),
                owner, normalizedTableName, normalizedColumnName);
        if (CollectionUtils.isEmpty(columns)) {
            return columns;
        }
        return columns.stream()
                .peek(column -> normalizeColumn(column, databaseName, owner, normalizedTableName))
                .collect(Collectors.toList());
    }

    private String normalizeColumnType(String dataType) {
        if (StringUtils.isBlank(dataType)) {
            return dataType;
        }
        return OscarTypeAliasEnum.normalize(dataType);
    }

    private String normalizeCatalog(String databaseName) {
        return StringUtils.isBlank(databaseName) ? null : normalizeIdentifier(databaseName);
    }

    private String[] tableTypes(String schemaName) {
        return isSystemSchema(schemaName) ? SYSTEM_SCHEMA_TABLE_TYPES : TABLE_TYPES;
    }

    private boolean isSystemSchema(String schemaName) {
        return getSystemSchemas().stream()
                .anyMatch(systemSchema -> StringUtils.equalsIgnoreCase(systemSchema, schemaName));
    }

    private void normalizeColumn(TableColumn column, String databaseName, String schemaName, String tableName) {
        column.setDatabaseName(StringUtils.defaultIfBlank(column.getDatabaseName(), databaseName));
        column.setSchemaName(StringUtils.defaultIfBlank(column.getSchemaName(), schemaName));
        column.setTableName(StringUtils.defaultIfBlank(column.getTableName(), tableName));
        column.setName(normalizeIdentifier(column.getName()));
        column.setColumnType(normalizeColumnType(column.getColumnType()));
        if (isTimestampType(column.getColumnType())) {
            if (column.getDecimalDigits() == null || column.getDecimalDigits() == INVALID_FRACTIONAL_DIGITS) {
                column.setColumnSize(null);
                column.setDecimalDigits(null);
            } else {
                column.setColumnSize(column.getDecimalDigits());
            }
        }
    }

    private void normalizeIndex(TableIndex index, String databaseName, String schemaName, String tableName,
                                Set<String> primaryKeyNames, Set<String> primaryKeyColumns) {
        index.setDatabaseName(StringUtils.defaultIfBlank(index.getDatabaseName(), databaseName));
        index.setSchemaName(StringUtils.defaultIfBlank(index.getSchemaName(), schemaName));
        index.setTableName(StringUtils.defaultIfBlank(index.getTableName(), tableName));
        if (CollectionUtils.isNotEmpty(index.getColumnList())) {
            index.setColumnList(index.getColumnList().stream()
                    .peek(column -> normalizeIndexColumn(column, databaseName, schemaName, tableName))
                    .sorted(Comparator.comparing(TableIndexColumn::getOrdinalPosition,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList()));
        }
        if (isPrimaryIndex(index, primaryKeyNames, primaryKeyColumns)) {
            index.setType(OscarIndexTypeEnum.PRIMARY_KEY.getName());
        } else if (Boolean.TRUE.equals(index.getUnique())) {
            index.setType(OscarIndexTypeEnum.UNIQUE.getName());
        } else {
            index.setType(OscarIndexTypeEnum.NORMAL.getName());
        }
    }

    private void normalizeIndexColumn(TableIndexColumn column, String databaseName, String schemaName, String tableName) {
        column.setDatabaseName(StringUtils.defaultIfBlank(column.getDatabaseName(), databaseName));
        column.setSchemaName(StringUtils.defaultIfBlank(column.getSchemaName(), schemaName));
        column.setTableName(StringUtils.defaultIfBlank(column.getTableName(), tableName));
        column.setColumnName(normalizeIdentifier(column.getColumnName()));
        if (OscarConstants.CATALOG_ASCENDING_SORT.equalsIgnoreCase(column.getAscOrDesc())) {
            column.setAscOrDesc(OscarConstants.INDEX_ASCENDING_SORT_SQL);
        } else if (OscarConstants.CATALOG_DESCENDING_SORT.equalsIgnoreCase(column.getAscOrDesc())) {
            column.setAscOrDesc(OscarConstants.INDEX_DESCENDING_SORT_SQL);
        }
    }

    private boolean isTimestampType(String dataType) {
        return StringUtils.isNotBlank(dataType) && TIMESTAMP_DATA_TYPES.contains(dataType.toUpperCase());
    }

    private boolean isPrimaryIndex(TableIndex index, Set<String> primaryKeyNames, Set<String> primaryKeyColumns) {
        if (StringUtils.isNotBlank(index.getName()) && primaryKeyNames.stream()
                .anyMatch(primaryKeyName -> StringUtils.equalsIgnoreCase(primaryKeyName, index.getName()))) {
            return true;
        }
        if (CollectionUtils.isEmpty(index.getColumnList()) || CollectionUtils.isEmpty(primaryKeyColumns)) {
            return false;
        }
        Set<String> indexColumns = index.getColumnList().stream()
                .map(TableIndexColumn::getColumnName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        return Boolean.TRUE.equals(index.getUnique()) && indexColumns.equals(primaryKeyColumns);
    }

    private String readSource(ResultSet resultSet) throws SQLException {
        StringBuilder bodyBuilder = new StringBuilder(SQLConstants.SPACE);
        while (resultSet.next()) {
            bodyBuilder.append(resultSet.getString(OscarConstants.CATALOG_SOURCE_TEXT))
                    .append(SQLConstants.LINE_SEPARATOR);
        }
        String body = bodyBuilder.toString().trim();
        return StringUtils.isBlank(body) ? SQLConstants.EMPTY : SQLConstants.SPACE + body;
    }

    private String buildTriggerDdl(ResultSet resultSet) throws SQLException {
        String owner = resultSet.getString(OscarConstants.CATALOG_TABLE_OWNER);
        String tableName = resultSet.getString(OscarConstants.CATALOG_TABLE_NAME);
        String triggerType = resultSet.getString(OscarConstants.CATALOG_TRIGGER_TYPE);
        String event = resultSet.getString(OscarConstants.CATALOG_TRIGGERING_EVENT);
        String body = resultSet.getString(OscarConstants.CATALOG_TRIGGER_BODY);
        StringBuilder ddl = new StringBuilder(SQLConstants.CREATE_OR_REPLACE_TRIGGER_SQL_PREFIX);
        ddl.append(qualifiedName(owner, resultSet.getString(OscarConstants.CATALOG_TRIGGER_NAME)))
                .append(SQLConstants.SPACE);
        if (StringUtils.isNotBlank(triggerType)) {
            ddl.append(triggerType.replace(SQLConstants.TRIGGER_EACH_ROW, SQLConstants.EMPTY).trim())
                    .append(SQLConstants.SPACE);
        }
        ddl.append(event).append(SQLConstants.SQL_ON).append(qualifiedName(owner, tableName))
                .append(SQLConstants.SPACE);
        if (StringUtils.containsIgnoreCase(triggerType, SQLConstants.TRIGGER_EACH_ROW)) {
            ddl.append(SQLConstants.TRIGGER_FOR_EACH_ROW_SQL);
        }
        ddl.append(body);
        return ddl.toString();
    }

}
