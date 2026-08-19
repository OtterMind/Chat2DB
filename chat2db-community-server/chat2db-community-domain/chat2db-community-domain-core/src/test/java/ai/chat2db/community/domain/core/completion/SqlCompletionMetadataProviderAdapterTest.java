package ai.chat2db.community.domain.core.completion;

import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionMetadataRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionMetadataResponse;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionParameterModeTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionStatusEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionMetadataScope;
import ai.chat2db.community.domain.core.converter.SqlCompletionConverter;
import ai.chat2db.community.domain.core.converter.SqlCompletionConverterImpl;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.community.domain.api.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.spi.model.request.FunctionMetadataRequest;
import ai.chat2db.spi.model.request.ProcedureMetadataRequest;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SqlCompletionMetadataProviderAdapterTest {

    private final SqlCompletionConverter converter = new SqlCompletionConverterImpl();

    @Test
    void legacyIdentifierProcessorUsesCompatibleAlwaysQuoteDefault() {
        BacktickIdentifierProcessor processor = new BacktickIdentifierProcessor();

        Assertions.assertEquals("`a``b`", processor.quoteIdentifierAlways("a`b"));
        Assertions.assertEquals("a`b",
                processor.removeIdentifierQuote(processor.quoteIdentifierAlways("a`b")));
    }

    @Test
    void listTablesUsesConverterAndPrefixFilter() {
        FakeMetaData metaData = new FakeMetaData();
        SqlCompletionMetadataProviderAdapter provider = newProvider(metaData);

        SqlCompletionMetadataResponse result = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.TABLE, SqlCompletionMetadataScope.empty(), "ord"));

        Assertions.assertEquals(SqlCompletionStatusEnum.SUCCESS.name(), result.getStatus());
        Assertions.assertEquals(1, result.getCandidates().size());
        SqlCompletionCandidate candidate = result.getCandidates().get(0);
        Assertions.assertEquals("orders", candidate.getLabel());
        Assertions.assertEquals("`orders`", candidate.getInsertText());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.TABLE, candidate.getType());
        Assertions.assertEquals("main", candidate.getDatabaseName());
        Assertions.assertEquals("public", candidate.getSchemaName());
        Assertions.assertEquals(" (main.public)", candidate.getDetail());
        Assertions.assertEquals("@local", candidate.getDescription());
        Assertions.assertEquals("main", metaData.lastDatabaseName);
        Assertions.assertEquals("public", metaData.lastSchemaName);
    }

    @Test
    void listColumnsRequiresTableScope() {
        SqlCompletionMetadataProviderAdapter provider = newProvider(new FakeMetaData());

        SqlCompletionMetadataResponse missingTable = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.COLUMN, SqlCompletionMetadataScope.empty(), ""));
        Assertions.assertTrue(missingTable.getCandidates().isEmpty());

        SqlCompletionMetadataResponse result = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.COLUMN,
                new SqlCompletionMetadataScope(null, null, "orders", null),
                "i"));

        Assertions.assertEquals(SqlCompletionStatusEnum.SUCCESS.name(), result.getStatus());
        Assertions.assertEquals(1, result.getCandidates().size());
        SqlCompletionCandidate candidate = result.getCandidates().get(0);
        Assertions.assertEquals("id", candidate.getLabel());
        Assertions.assertEquals("`id`", candidate.getInsertText());
        Assertions.assertEquals("orders", candidate.getTableName());
        Assertions.assertEquals("BIGINT", candidate.getDataType());
    }

    @Test
    void mysqlQualifiedDatabaseScopeOverridesConsoleDatabaseForTableLookup() {
        FakeMetaData metaData = new FakeMetaData();
        SqlCompletionMetadataProviderAdapter provider = newMysqlProvider(metaData);

        SqlCompletionMetadataResponse result = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.TABLE_VIEW,
                new SqlCompletionMetadataScope(null, "information_schema", null, null),
                "access_"));

        Assertions.assertEquals(SqlCompletionStatusEnum.SUCCESS.name(), result.getStatus());
        Assertions.assertEquals("information_schema", metaData.lastDatabaseName);
        Assertions.assertNull(metaData.lastSchemaName);
        Assertions.assertEquals("access_control_apply_record", result.getCandidates().get(0).getLabel());
        Assertions.assertEquals("information_schema", result.getCandidates().get(0).getDatabaseName());
        Assertions.assertNull(result.getCandidates().get(0).getSchemaName());
        Assertions.assertEquals(" (information_schema)", result.getCandidates().get(0).getDetail());
    }

    @Test
    void mysqlQualifiedDatabaseScopeOverridesConsoleDatabaseForColumnLookup() {
        FakeMetaData metaData = new FakeMetaData();
        SqlCompletionMetadataProviderAdapter provider = newMysqlProvider(metaData);

        SqlCompletionMetadataResponse result = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.COLUMN,
                new SqlCompletionMetadataScope(null, "information_schema", "access_control_apply_record", null),
                "id"));

        Assertions.assertEquals(SqlCompletionStatusEnum.SUCCESS.name(), result.getStatus());
        Assertions.assertEquals("information_schema", metaData.lastDatabaseName);
        Assertions.assertNull(metaData.lastSchemaName);
        Assertions.assertEquals("access_control_apply_record", metaData.lastTableName);
        SqlCompletionCandidate candidate = result.getCandidates().get(0);
        Assertions.assertEquals("id", candidate.getLabel());
        Assertions.assertEquals("information_schema", candidate.getDatabaseName());
        Assertions.assertNull(candidate.getSchemaName());
        Assertions.assertEquals("access_control_apply_record", candidate.getTableName());
    }

    @Test
    void listTriggersUsesConverterAndPrefixFilter() {
        SqlCompletionMetadataProviderAdapter provider = newProvider(new FakeMetaData());

        SqlCompletionMetadataResponse result = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.TRIGGER, SqlCompletionMetadataScope.empty(), "trg_"));

        Assertions.assertEquals(SqlCompletionStatusEnum.SUCCESS.name(), result.getStatus());
        Assertions.assertEquals(1, result.getCandidates().size());
        SqlCompletionCandidate candidate = result.getCandidates().get(0);
        Assertions.assertEquals("trg_orders_insert", candidate.getLabel());
        Assertions.assertEquals("`trg_orders_insert`", candidate.getInsertText());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.TRIGGER, candidate.getType());
        Assertions.assertEquals("main", candidate.getDatabaseName());
        Assertions.assertEquals("public", candidate.getSchemaName());
        Assertions.assertEquals(" (main.public)", candidate.getDetail());
        Assertions.assertEquals("@local", candidate.getDescription());
        Assertions.assertEquals("INSERT", candidate.getObjectType());
    }

    @Test
    void unsupportedCandidateTypeReturnsUnsupported() {
        SqlCompletionMetadataProviderAdapter provider = newProvider(new FakeMetaData());

        SqlCompletionMetadataResponse result = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.INDEX, SqlCompletionMetadataScope.empty(), ""));

        Assertions.assertEquals(SqlCompletionStatusEnum.UNSUPPORTED.name(), result.getStatus());
        Assertions.assertEquals("sql.completion.metadata.unsupported", result.getReasonCode());
    }

    @Test
    void eventMetadataIsUnsupportedUntilMetadataSpiCanListEvents() {
        SqlCompletionMetadataProviderAdapter provider = newProvider(new FakeMetaData());

        SqlCompletionMetadataResponse result = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.EVENT, SqlCompletionMetadataScope.empty(), ""));

        Assertions.assertEquals(SqlCompletionStatusEnum.UNSUPPORTED.name(), result.getStatus());
        Assertions.assertEquals("sql.completion.metadata.unsupported", result.getReasonCode());
    }

    @Test
    void routineParametersUseMetadataSpiAndConverter() {
        SqlCompletionMetadataProviderAdapter provider = newProvider(new FakeMetaData());

        SqlCompletionMetadataResponse functionResult = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.PARAMETER,
                new SqlCompletionMetadataScope(null, null, null, "add_numbers"),
                "",
                SqlCompletionCandidateTypeEnum.FUNCTION));
        SqlCompletionMetadataResponse procedureResult = provider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.PARAMETER,
                new SqlCompletionMetadataScope("main", "public", null, "refresh_orders"),
                "",
                SqlCompletionCandidateTypeEnum.PROCEDURE));

        Assertions.assertEquals(SqlCompletionStatusEnum.SUCCESS.name(), functionResult.getStatus());
        Assertions.assertEquals(List.of("a", "b"), functionResult.getCandidates().stream()
                .map(SqlCompletionCandidate::getLabel)
                .toList());
        Assertions.assertTrue(functionResult.getCandidates().stream()
                .allMatch(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.PARAMETER));
        Assertions.assertEquals("INT", functionResult.getCandidates().get(0).getDataType());
        Assertions.assertEquals(SqlCompletionParameterModeTypeEnum.IN,
                functionResult.getCandidates().get(0).getParameterMode());

        Assertions.assertEquals(SqlCompletionStatusEnum.SUCCESS.name(), procedureResult.getStatus());
        Assertions.assertEquals(List.of("tenant_id", "force"), procedureResult.getCandidates().stream()
                .map(SqlCompletionCandidate::getColumnName)
                .toList());
        Assertions.assertEquals("BOOLEAN", procedureResult.getCandidates().get(1).getDetail());
        Assertions.assertEquals(SqlCompletionParameterModeTypeEnum.OUT,
                procedureResult.getCandidates().get(1).getParameterMode());
    }

    private SqlCompletionMetadataProviderAdapter newProvider(FakeMetaData metaData) {
        SqlCompletionMetadataContext context = SqlCompletionMetadataContext.builder()
                .dataSourceId(1L)
                .databaseName("main")
                .schemaName("public")
                .datasourceName("local")
                .metaData(metaData)
                .identifierProcessor(new BacktickIdentifierProcessor())
                .build();
        return new SqlCompletionMetadataProviderAdapter(context, converter);
    }

    private SqlCompletionMetadataProviderAdapter newMysqlProvider(FakeMetaData metaData) {
        DBConfig dbConfig = new DBConfig();
        dbConfig.setDbType("MYSQL");
        SqlCompletionMetadataContext context = SqlCompletionMetadataContext.builder()
                .dataSourceId(1L)
                .databaseName("enterprise_gateway_dev")
                .schemaName(null)
                .datasourceName("local")
                .dbConfig(dbConfig)
                .metaData(metaData)
                .identifierProcessor(new BacktickIdentifierProcessor())
                .build();
        return new SqlCompletionMetadataProviderAdapter(context, converter);
    }

    private static final class BacktickIdentifierProcessor implements ISQLIdentifierProcessor {
        @Override
        public boolean isValidIdentifier(String identifier) {
            return true;
        }

        @Override
        public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
            return false;
        }

        @Override
        public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
            return quoteIdentifier(identifier);
        }

        @Override
        public String quoteIdentifier(String identifier) {
            return identifier == null ? null : "`" + identifier.replace("`", "``") + "`";
        }

        @Override
        public String removeIdentifierQuote(String identifier) {
            if (identifier == null || identifier.length() < 2
                    || !identifier.startsWith("`") || !identifier.endsWith("`")) {
                return identifier;
            }
            return identifier.substring(1, identifier.length() - 1).replace("``", "`");
        }

        @Override
        public String quoteIdentifierIgnoreCase(String identifier) {
            return quoteIdentifier(identifier);
        }

        @Override
        public boolean isQuoteIdentifier(String identifier) {
            return identifier != null && identifier.startsWith("`") && identifier.endsWith("`");
        }

        @Override
        public String convertIdentifierCase(String identifier) {
            return identifier;
        }

        @Override
        public String escapeString(String str) {
            return str;
        }
    }

    private static final class FakeMetaData extends DefaultMetaService implements IDbMetaData {
        private String lastDatabaseName;
        private String lastSchemaName;
        private String lastTableName;

        @Override
        public List<Database> databases(Connection connection) {
            return List.of(Database.builder().name("main").build());
        }

        @Override
        public List<Schema> schemas(Connection connection, String databaseName) {
            lastDatabaseName = databaseName;
            return List.of(Schema.builder().databaseName(databaseName).name("public").build());
        }

        @Override
        public List<Table> tables(Connection connection, TablesRequest request) {
            String databaseName = request.getDatabaseName();
            String schemaName = request.getSchemaName();
            String tableName = request.getTableName();
            lastDatabaseName = databaseName;
            lastSchemaName = schemaName;
            lastTableName = tableName;
            return List.of(
                    Table.builder().databaseName(databaseName).schemaName(schemaName)
                            .name("access_control_apply_record").type("TABLE").build(),
                    Table.builder().databaseName(databaseName).schemaName(schemaName).name("orders").type("TABLE").build(),
                    Table.builder().databaseName(databaseName).schemaName(schemaName).name("customers").type("TABLE").build());
        }

        @Override
        public List<Table> views(Connection connection, String databaseName, String schemaName) {
            lastDatabaseName = databaseName;
            lastSchemaName = schemaName;
            return List.of(Table.builder().databaseName(databaseName).schemaName(schemaName).name("order_view").build());
        }

        @Override
        public List<TableColumn> columns(Connection connection, TableMetadataRequest request) {
            String databaseName = request.getDatabaseName();
            String schemaName = request.getSchemaName();
            String tableName = request.getTableName();
            lastDatabaseName = databaseName;
            lastSchemaName = schemaName;
            lastTableName = tableName;
            return List.of(
                    TableColumn.builder().databaseName(databaseName).schemaName(schemaName).tableName(tableName)
                            .name("id").columnType("BIGINT").ordinalPosition(1).build(),
                    TableColumn.builder().databaseName(databaseName).schemaName(schemaName).tableName(tableName)
                            .name("status").columnType("VARCHAR").ordinalPosition(2).build());
        }

        @Override
        public List<Function> functions(Connection connection, String databaseName, String schemaName) {
            return List.of(Function.builder().databaseName(databaseName).schemaName(schemaName).functionName("lower").build());
        }

        @Override
        public List<Procedure> procedures(Connection connection, String databaseName, String schemaName) {
            return List.of(Procedure.builder().databaseName(databaseName).schemaName(schemaName).procedureName("refresh").build());
        }

        @Override
        public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
            return new BacktickIdentifierProcessor();
        }

        @Override
        public List<String> viewNames(Connection connection, String databaseName, String schemaName) {
            return List.of();
        }

        @Override
        public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
            return List.of(Trigger.builder().databaseName(databaseName).schemaName(schemaName)
                    .triggerName("trg_orders_insert").eventManipulation("INSERT").build());
        }

        @Override
        public List<Type> types(Connection connection) {
            return List.of();
        }

        @Override
        public ISqlBuilder getSqlBuilder() {
            return null;
        }

        @Override
        public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
            return null;
        }

        @Override
        public String getMetaDataName(String... names) {
            return null;
        }

        @Override
        public IValueProcessor getValueProcessor() {
            return null;
        }

        @Override
        public ICommandExecutor getCommandExecutor() {
            return null;
        }

        @Override
        public List<String> getSystemDatabases() {
            return List.of();
        }

        @Override
        public List<String> getSystemSchemas() {
            return List.of();
        }

        @Override
        public List<FunctionParameter> getFunctionParameters(Connection connection, FunctionMetadataRequest request) {
            String databaseName = request.getDatabaseName();
            String schemaName = request.getSchemaName();
            String functionName = request.getFunctionName();
            if (!"main".equals(databaseName) || !"public".equals(schemaName) || !"add_numbers".equals(functionName)) {
                return List.of();
            }
            FunctionParameter a = new FunctionParameter();
            a.setColumnName("a");
            a.setTypeName("INT");
            a.setColumnType(DatabaseMetaData.functionColumnIn);
            a.setOrdinalPosition(1);
            FunctionParameter b = new FunctionParameter();
            b.setColumnName("b");
            b.setTypeName("INT");
            b.setColumnType(DatabaseMetaData.functionColumnIn);
            b.setOrdinalPosition(2);
            return List.of(b, a).stream()
                    .sorted((left, right) -> Integer.compare(left.getOrdinalPosition(), right.getOrdinalPosition()))
                    .toList();
        }

        @Override
        public List<ProcedureParameter> getProcedureParameters(Connection connection, ProcedureMetadataRequest request) {
            String databaseName = request.getDatabaseName();
            String schemaName = request.getSchemaName();
            String procedureName = request.getProcedureName();
            if (!"main".equals(databaseName) || !"public".equals(schemaName) || !"refresh_orders".equals(procedureName)) {
                return List.of();
            }
            ProcedureParameter tenant = new ProcedureParameter();
            tenant.setColumnName("tenant_id");
            tenant.setTypeName("BIGINT");
            tenant.setColumnType(DatabaseMetaData.procedureColumnIn);
            tenant.setOrdinalPosition(1);
            ProcedureParameter force = new ProcedureParameter();
            force.setColumnName("force");
            force.setTypeName("BOOLEAN");
            force.setColumnType(DatabaseMetaData.procedureColumnOut);
            force.setOrdinalPosition(2);
            return List.of(tenant, force);
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
            return null;
        }

        @Override
        public ModifyViewConfiguration viewMeta(String databaseName, String schemaName) {
            return null;
        }

        @Override
        public Boolean supportCrossSchema() {
            return true;
        }

        @Override
        public Boolean supportCrossDatabase() {
            return true;
        }
    }
}
