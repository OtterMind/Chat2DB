package ai.chat2db.spi;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.view.ModifyViewConfiguration;
import ai.chat2db.community.tools.wrapper.result.PageResult;
import ai.chat2db.spi.model.request.*;
import ai.chat2db.spi.model.response.TablesPageResponse;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.SqlUtils;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


public class DefaultMetaService implements IDbMetaData {

    private static final ISQLIdentifierProcessor DEFAULT_SQL_IDENTIFIER_PROCESSOR = new DefaultSQLIdentifierProcessor();
    private static final String[] TABLE_TYPES = {"TABLE", "SYSTEM TABLE", "BASE TABLE"};
    private static final String[] VIEW_TYPES = {"VIEW"};

    @Override
    public List<Database> databases(Connection connection) {
        List<Database> databases = DefaultSQLExecutor.getInstance().databases(connection);
        List<String> systemDatabases = getSystemDatabases();
        if (CollectionUtils.isEmpty(databases) || CollectionUtils.isEmpty(systemDatabases)) {
            return databases;
        }
        databases.forEach(database -> {
            if (systemDatabases.contains(database.getName())) {
                database.setSystem(true);
            }
        });
        return databases;
    }

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = DefaultSQLExecutor.getInstance().schemas(connection, databaseName, null);
        if (StringUtils.isNotBlank(databaseName) && CollectionUtils.isNotEmpty(schemas)) {
            for (Schema schema : schemas) {
                if (StringUtils.isBlank(schema.getDatabaseName())) {
                    schema.setDatabaseName(databaseName);
                }
            }
        }
        List<String> systemSchemas = getSystemSchemas();
        if (CollectionUtils.isEmpty(schemas) || CollectionUtils.isEmpty(systemSchemas)) {
            return schemas;
        }
        schemas.forEach(schema -> {
            if (systemSchemas.contains(schema.getName())) {
                schema.setSystem(true);
            }
        });
        return schemas;
    }

    @Override
    public String tableDDL(Connection connection, TableMetadataRequest tableMetadataRequest) {
        return tableDDL(connection, tableMetadataRequest.getDatabaseName(), tableMetadataRequest.getSchemaName(),
                tableMetadataRequest.getTableName());
    }

    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        throw unsupported("table DDL");
    }

    @Override
    public List<Table> tables(Connection connection, TablesRequest tablesRequest) {
        return tables(connection, tablesRequest.getDatabaseName(), tablesRequest.getSchemaName(),
                tablesRequest.getTableName());
    }

    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        return DefaultSQLExecutor.getInstance().tables(connection, blankToNull(databaseName), blankToNull(schemaName),
                tableName, TABLE_TYPES);
    }

    @Override
    public List<String> tableNames(Connection connection, TablesRequest tablesRequest) {
        return tableNames(connection, tablesRequest.getDatabaseName(), tablesRequest.getSchemaName(),
                tablesRequest.getTableName());
    }

    public List<String> tableNames(Connection connection, String databaseName, String schemaName, String tableName) {
        return DefaultSQLExecutor.getInstance().tableNames(connection, blankToNull(databaseName), blankToNull(schemaName),
                tableName, TABLE_TYPES);
    }

    @Override
    public TablesPageResponse tables(Connection connection, TablesPageRequest tablesPageRequest) {
        List<Table> tables = tables(connection, new TablesRequest(tablesPageRequest.getDatabaseName(),
                tablesPageRequest.getSchemaName(), tablesPageRequest.getTableNamePattern()));
        if (CollectionUtils.isEmpty(tables)) {
            return TablesPageResponse.of(tables, 0L, tablesPageRequest.getPageNo(), tablesPageRequest.getPageSize());
        }
        tables.sort(Comparator.comparing(Table::getName, String.CASE_INSENSITIVE_ORDER));
        List<Table> result = tables.stream()
                .skip((long) (tablesPageRequest.getPageNo() - 1) * tablesPageRequest.getPageSize())
                .limit(tablesPageRequest.getPageSize())
                .collect(Collectors.toList());
        return TablesPageResponse.of(result, (long) tables.size(), tablesPageRequest.getPageNo(),
                tablesPageRequest.getPageSize());
    }

    public PageResult<Table> tables(Connection connection, String databaseName, String schemaName, String tableNamePattern, int pageNo, int pageSize) {
        List<Table> tables = tables(connection, databaseName, schemaName, tableNamePattern);
        if (CollectionUtils.isEmpty(tables)) {
            return PageResult.of(tables, 0L, pageNo, pageSize);
        }
        tables.sort(Comparator.comparing(Table::getName, String.CASE_INSENSITIVE_ORDER));
        List result = tables.stream().skip((pageNo - 1) * pageSize).limit(pageSize).collect(Collectors.toList());
        return PageResult.of(result, (long) tables.size(), pageNo, pageSize);
    }

    @Override
    public Table view(Connection connection, ViewMetadataRequest viewMetadataRequest) {
        return view(connection, viewMetadataRequest.getDatabaseName(), viewMetadataRequest.getSchemaName(),
                viewMetadataRequest.getViewName());
    }

    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        return getTable(views(connection, databaseName, schemaName, viewName), viewName);
    }

    @Override
    public List<Table> views(Connection connection, String databaseName, String schemaName) {
        return DefaultSQLExecutor.getInstance().tables(connection, blankToNull(databaseName), blankToNull(schemaName),
                null, VIEW_TYPES);
    }

    @Override
    public List<Table> views(Connection connection, ViewMetadataRequest viewMetadataRequest) {
        return views(connection, viewMetadataRequest.getDatabaseName(), viewMetadataRequest.getSchemaName(),
                viewMetadataRequest.getViewName());
    }

    public List<Table> views(Connection connection, String databaseName, String schemaName, String viewName) {
        return DefaultSQLExecutor.getInstance().tables(connection, blankToNull(databaseName), blankToNull(schemaName),
                viewName, VIEW_TYPES);
    }

    @Override
    public List<String> viewNames(Connection connection, String databaseName, String schemaName) {
        return DefaultSQLExecutor.getInstance().tableNames(connection, blankToNull(databaseName),
                blankToNull(schemaName), null, VIEW_TYPES);
    }

    @Override
    public List<Function> functions(Connection connection, String databaseName, String schemaName) {
        List<Function> functions = DefaultSQLExecutor.getInstance().functions(connection, blankToNull(databaseName),
                blankToNull(schemaName));
        if (CollectionUtils.isEmpty(functions)) {
            return functions;
        }
        return functions.stream().filter(function -> StringUtils.isNotBlank(function.getFunctionName())).map(function -> {
            String functionName = function.getFunctionName();
            function.setFunctionName(functionName.trim());
            return function;
        }).collect(Collectors.toList());
    }

    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        return Lists.newArrayList();
    }

    @Override
    public List<Event> events(Connection connection, String databaseName, String schemaName) {
        return Lists.newArrayList();
    }

    @Override
    public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
        List<Procedure> procedures = DefaultSQLExecutor.getInstance().procedures(connection, blankToNull(databaseName),
                blankToNull(schemaName));

        if (CollectionUtils.isEmpty(procedures)) {
            return procedures;
        }
        return procedures.stream().filter(function -> StringUtils.isNotBlank(function.getProcedureName())).map(procedure -> {
            String procedureName = procedure.getProcedureName();
            procedure.setProcedureName(procedureName.trim());
            return procedure;
        }).collect(Collectors.toList());
    }

    @Override
    public List<TableColumn> columns(Connection connection, TableMetadataRequest tableMetadataRequest) {
        return columns(connection, tableMetadataRequest.getDatabaseName(), tableMetadataRequest.getSchemaName(),
                tableMetadataRequest.getTableName());
    }

    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableColumn> columns = DefaultSQLExecutor.getInstance().columns(connection, blankToNull(databaseName),
                blankToNull(schemaName), tableName, null);
        if (CollectionUtils.isNotEmpty(columns)) {
            for (TableColumn column : columns) {
                String columnType = SqlUtils.removeDigits(column.getColumnType());
                column.setColumnType(columnType);
            }
        }
        return columns;
    }

    @Override
    public List<TableColumn> columns(Connection connection, ColumnMetadataRequest columnMetadataRequest) {
        return columns(connection, columnMetadataRequest.getDatabaseName(), columnMetadataRequest.getSchemaName(),
                columnMetadataRequest.getTableName(), columnMetadataRequest.getColumnName());
    }

    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName, String columnName) {
        return DefaultSQLExecutor.getInstance().columns(connection, blankToNull(databaseName), blankToNull(schemaName),
                tableName, columnName);
    }

    @Override
    public List<TableIndex> indexes(Connection connection, TableMetadataRequest tableMetadataRequest) {
        return indexes(connection, tableMetadataRequest.getDatabaseName(), tableMetadataRequest.getSchemaName(),
                tableMetadataRequest.getTableName());
    }

    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        return DefaultSQLExecutor.getInstance().indexes(connection, blankToNull(databaseName), blankToNull(schemaName),
                tableName);
    }

    @Override
    public Function function(Connection connection, FunctionMetadataRequest functionMetadataRequest) {
        return function(connection, functionMetadataRequest.getDatabaseName(), functionMetadataRequest.getSchemaName(),
                functionMetadataRequest.getFunctionName());
    }

    public Function function(Connection connection, String databaseName, String schemaName, String functionName) {
        if (StringUtils.isBlank(functionName)) {
            return null;
        }
        List<Function> functions = functions(connection, databaseName, schemaName);
        if (CollectionUtils.isEmpty(functions)) {
            return null;
        }
        return functions.stream()
                .filter(function -> Objects.equals(function.getFunctionName(), functionName))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Trigger trigger(Connection connection, TriggerMetadataRequest triggerMetadataRequest) {
        return trigger(connection, triggerMetadataRequest.getDatabaseName(), triggerMetadataRequest.getSchemaName(),
                triggerMetadataRequest.getTriggerName());
    }

    public Trigger trigger(Connection connection, String databaseName, String schemaName, String triggerName) {
        if (StringUtils.isBlank(triggerName)) {
            return null;
        }
        List<Trigger> triggers = triggers(connection, databaseName, schemaName);
        if (CollectionUtils.isEmpty(triggers)) {
            return null;
        }
        return triggers.stream()
                .filter(trigger -> Objects.equals(trigger.getTriggerName(), triggerName))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Event event(Connection connection, EventMetadataRequest eventMetadataRequest) {
        return event(connection, eventMetadataRequest.getDatabaseName(), eventMetadataRequest.getSchemaName(),
                eventMetadataRequest.getEventName());
    }

    public Event event(Connection connection, String databaseName, String schemaName, String eventName) {
        if (StringUtils.isBlank(eventName)) {
            return null;
        }
        List<Event> events = events(connection, databaseName, schemaName);
        if (CollectionUtils.isEmpty(events)) {
            return null;
        }
        return events.stream()
                .filter(event -> Objects.equals(event.getEventName(), eventName))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Procedure procedure(Connection connection, ProcedureMetadataRequest procedureMetadataRequest) {
        return procedure(connection, procedureMetadataRequest.getDatabaseName(), procedureMetadataRequest.getSchemaName(),
                procedureMetadataRequest.getProcedureName());
    }

    public Procedure procedure(Connection connection, String databaseName, String schemaName, String procedureName) {
        if (StringUtils.isBlank(procedureName)) {
            return null;
        }
        List<Procedure> procedures = procedures(connection, databaseName, schemaName);
        if (CollectionUtils.isEmpty(procedures)) {
            return null;
        }
        return procedures.stream()
                .filter(procedure -> Objects.equals(procedure.getProcedureName(), procedureName))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<Type> types(Connection connection) {
        return DefaultSQLExecutor.getInstance().types(connection);
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new DefaultSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder().build();
    }

    @Override
    public String getMetaDataName(String... names) {
        return Arrays.stream(names).filter(StringUtils::isNotBlank).collect(Collectors.joining("."));
    }

    @Override
    public IValueProcessor getValueProcessor() {
        return new DefaultValueProcessor();
    }

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return DEFAULT_SQL_IDENTIFIER_PROCESSOR;
    }

    @Override
    public ICommandExecutor getCommandExecutor() {
        return DefaultSQLExecutor.getInstance();
    }

    @Override
    public List<String> getSystemDatabases() {
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        if(dbConfig == null){
            return Lists.newArrayList();
        }else {
            List<String> strings = dbConfig.getSystemDatabases();
            return strings == null ? Lists.newArrayList() : strings;
        }
    }

    @Override
    public List<String> getSystemSchemas() {
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        if(dbConfig == null){
            return Lists.newArrayList();
        }else {
            List<String> strings = dbConfig.getSystemSchemas();
            return strings == null ? Lists.newArrayList() : strings;
        }
    }

    @Override
    public List<FunctionParameter> getFunctionParameters(Connection connection, FunctionMetadataRequest functionMetadataRequest) {
        return getFunctionParameters(connection, functionMetadataRequest.getDatabaseName(),
                functionMetadataRequest.getSchemaName(),
                functionMetadataRequest.getFunctionName());
    }

    public List<FunctionParameter> getFunctionParameters(Connection connection, String databaseName, String schemaName,
            String functionName) {
        return DefaultSQLExecutor.getInstance().getFunctionParameters(connection, blankToNull(databaseName),
                blankToNull(schemaName), functionName);
    }

    @Override
    public List<ProcedureParameter> getProcedureParameters(Connection connection, ProcedureMetadataRequest procedureMetadataRequest) {
        return getProcedureParameters(connection, procedureMetadataRequest.getDatabaseName(),
                procedureMetadataRequest.getSchemaName(),
                procedureMetadataRequest.getProcedureName());
    }

    public List<ProcedureParameter> getProcedureParameters(Connection connection, String databaseName, String schemaName,
            String procedureName) {
        return DefaultSQLExecutor.getInstance().getProcedureParameters(connection, blankToNull(databaseName),
                blankToNull(schemaName), procedureName);
    }

    @Override
    public List<ForeignKeyInfo> getImportedKeys(Connection connection, TableMetadataRequest tableMetadataRequest) {
        return getImportedKeys(connection, tableMetadataRequest.getDatabaseName(), tableMetadataRequest.getSchemaName(),
                tableMetadataRequest.getTableName());
    }

    public List<ForeignKeyInfo> getImportedKeys(Connection connection, String databaseName, String schemaName,
            String tableName) {
        return DefaultSQLExecutor.getInstance().getImportedKeys(connection, blankToNull(databaseName),
                blankToNull(schemaName), tableName);
    }

    @Override
    public List<ForeignKeyInfo> getExportedKeys(Connection connection, TableMetadataRequest tableMetadataRequest) {
        return getExportedKeys(connection, tableMetadataRequest.getDatabaseName(), tableMetadataRequest.getSchemaName(),
                tableMetadataRequest.getTableName());
    }

    public List<ForeignKeyInfo> getExportedKeys(Connection connection, String databaseName, String schemaName,
            String tableName) {
        return DefaultSQLExecutor.getInstance().getExportedKeys(connection, blankToNull(databaseName),
                blankToNull(schemaName), tableName);
    }

    @Override
    public List<PrimaryKey> getPrimaryKeys(Connection connection, TableMetadataRequest tableMetadataRequest) {
        return getPrimaryKeys(connection, tableMetadataRequest.getDatabaseName(), tableMetadataRequest.getSchemaName(),
                tableMetadataRequest.getTableName());
    }

    public List<PrimaryKey> getPrimaryKeys(Connection connection, String databaseName, String schemaName,
            String tableName) {
        return DefaultSQLExecutor.getInstance().getPrimaryKeys(connection, blankToNull(databaseName),
                blankToNull(schemaName), tableName);
    }

    @Override
    public String getDefaultDatabaseName(Connection connection, String consoleDatabaseName) {
        return consoleDatabaseName;
    }

    @Override
    public String getDefaultSchemaName(Connection connection, String consoleSchemaName) {
        return consoleSchemaName;
    }

    @Override
    public Table getTable(List<Table> tables, String tableName) {
        if (StringUtils.isBlank(tableName) || CollectionUtils.isEmpty(tables)) {
            return null;
        }
        for (Table table : tables) {
            if (Objects.equals(table.getName(), tableName)) {
                return table;
            }
        }
        return null;
    }

    @Override
    public ModifyViewConfiguration viewMeta(String databaseName, String schemaName) {
        throw unsupported("view modification metadata");
    }

    @Override
    public Boolean supportCrossSchema() {
        return Boolean.FALSE;
    }

    private static String blankToNull(String value) {
        return StringUtils.isEmpty(value) ? null : value;
    }

    @Override
    public Boolean supportCrossDatabase() {
        return Boolean.FALSE;
    }

    private UnsupportedOperationException unsupported(String capability) {
        return new UnsupportedOperationException("Default metadata does not support " + capability);
    }
}
