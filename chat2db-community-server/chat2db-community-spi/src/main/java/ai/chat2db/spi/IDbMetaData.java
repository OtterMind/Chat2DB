package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.metadata.Function;
import ai.chat2db.community.domain.api.model.metadata.FunctionParameter;
import ai.chat2db.community.domain.api.model.metadata.PrimaryKey;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.community.domain.api.model.metadata.ProcedureParameter;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableMeta;
import ai.chat2db.community.domain.api.model.metadata.Trigger;
import ai.chat2db.community.domain.api.model.metadata.Type;
import ai.chat2db.community.domain.api.model.result.ResultSetEditorMetadata;
import ai.chat2db.community.domain.api.model.view.ModifyViewConfiguration;
import ai.chat2db.spi.enums.UnsupportedKeyOperationsEnum;
import ai.chat2db.spi.model.request.ColumnMetadataRequest;
import ai.chat2db.spi.model.request.FunctionMetadataRequest;
import ai.chat2db.spi.model.request.ProcedureMetadataRequest;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesPageRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.model.request.TriggerMetadataRequest;
import ai.chat2db.spi.model.request.ViewMetadataRequest;
import ai.chat2db.spi.model.response.TablesPageResponse;
import jakarta.validation.constraints.NotEmpty;

import java.sql.Connection;
import java.util.List;

/**
 * Provides dialect-specific database metadata and helper components.
 */
public interface IDbMetaData {

    default IKeyOperations keyOperations() {
        return UnsupportedKeyOperationsEnum.INSTANCE;
    }

    List<Database> databases(Connection connection);

    List<Schema> schemas(Connection connection, String databaseName);

    String tableDDL(Connection connection, TableMetadataRequest tableMetadataRequest);

    List<Table> tables(Connection connection, TablesRequest tablesRequest);

    List<String> tableNames(Connection connection, TablesRequest tablesRequest);

    TablesPageResponse tables(Connection connection, TablesPageRequest tablesPageRequest);

    Table view(Connection connection, ViewMetadataRequest viewMetadataRequest);

    List<String> viewNames(Connection connection, @NotEmpty String databaseName, String schemaName);

    List<Table> views(Connection connection, @NotEmpty String databaseName, String schemaName);

    List<Table> views(Connection connection, ViewMetadataRequest viewMetadataRequest);

    List<Function> functions(Connection connection, @NotEmpty String databaseName, String schemaName);

    List<Trigger> triggers(Connection connection, @NotEmpty String databaseName, String schemaName);

    List<Procedure> procedures(Connection connection, @NotEmpty String databaseName, String schemaName);

    List<TableColumn> columns(Connection connection, TableMetadataRequest tableMetadataRequest);

    List<TableColumn> columns(Connection connection, ColumnMetadataRequest columnMetadataRequest);

    List<TableIndex> indexes(Connection connection, TableMetadataRequest tableMetadataRequest);

    Function function(Connection connection, FunctionMetadataRequest functionMetadataRequest);

    Trigger trigger(Connection connection, TriggerMetadataRequest triggerMetadataRequest);

    Procedure procedure(Connection connection, ProcedureMetadataRequest procedureMetadataRequest);

    List<Type> types(Connection connection);

    ISqlBuilder getSqlBuilder();

    TableMeta getTableMeta(String databaseName, String schemaName, String tableName);

    String getMetaDataName(String... names);

    /**
     * Builds the table reference used inside generated SQL (select-table, cell value
     * lookup, ...). Dialects that reject fully qualified names override this to drop
     * the database or schema part; the default keeps the historical
     * database.schema.table behaviour.
     */
    default String getQualifiedTableName(String databaseName, String schemaName, String tableName) {
        return getMetaDataName(databaseName, schemaName, tableName);
    }

    IValueProcessor getValueProcessor();

    default String resolveResultSetEditorType(String typeName, Integer type) {
        return "TEXT";
    }

    /**
     * Resolves editor metadata from an already-loaded table column. Dialects that do not expose
     * structured editor options naturally keep the legacy editor type with an empty option list.
     */
    default ResultSetEditorMetadata resolveResultSetEditorMetadata(TableColumn column) {
        String editorType = column == null ? "TEXT"
                : resolveResultSetEditorType(column.getColumnType(), column.getDataType());
        return ResultSetEditorMetadata.builder()
                .editorType(editorType)
                .editorOptions(List.of())
                .build();
    }

    ISQLIdentifierProcessor getSQLIdentifierProcessor();

    ICommandExecutor getCommandExecutor();

    List<String> getSystemDatabases();

    List<String> getSystemSchemas();

    List<FunctionParameter> getFunctionParameters(Connection connection, FunctionMetadataRequest functionMetadataRequest);

    List<ProcedureParameter> getProcedureParameters(Connection connection,
            ProcedureMetadataRequest procedureMetadataRequest);

    List<ForeignKeyInfo> getImportedKeys(Connection connection, TableMetadataRequest tableMetadataRequest);

    List<ForeignKeyInfo> getExportedKeys(Connection connection, TableMetadataRequest tableMetadataRequest);

    List<PrimaryKey> getPrimaryKeys(Connection connection, TableMetadataRequest tableMetadataRequest);

    String getDefaultDatabaseName(Connection connection, String consoleDatabaseName);

    String getDefaultSchemaName(Connection connection, String consoleSchemaName);

    Table getTable(List<Table> tables, String tableName);

    ModifyViewConfiguration viewMeta(String databaseName, String schemaName);

    Boolean supportCrossSchema();

    Boolean supportCrossDatabase();
}
