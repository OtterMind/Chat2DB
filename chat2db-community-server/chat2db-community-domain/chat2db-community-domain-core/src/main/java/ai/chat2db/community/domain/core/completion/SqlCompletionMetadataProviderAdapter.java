package ai.chat2db.community.domain.core.completion;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.service.db.ISqlCompletionMetadataProvider;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionMetadataRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionMetadataResponse;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionMetadataScope;
import ai.chat2db.community.domain.core.cache.MemoryCacheManage;
import ai.chat2db.community.domain.core.converter.SqlCompletionConverter;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Function;
import ai.chat2db.community.domain.api.model.metadata.FunctionParameter;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.community.domain.api.model.metadata.ProcedureParameter;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.Trigger;
import ai.chat2db.spi.model.request.FunctionMetadataRequest;
import ai.chat2db.spi.model.request.ProcedureMetadataRequest;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import static ai.chat2db.community.domain.core.cache.CacheKey.getColumnKey;
import static ai.chat2db.community.domain.core.cache.CacheKey.getTableKey;


@RequiredArgsConstructor
public class SqlCompletionMetadataProviderAdapter implements ISqlCompletionMetadataProvider {

    private final SqlCompletionMetadataContext context;
    private final SqlCompletionConverter converter;

    @Override
    public SqlCompletionMetadataResponse list(DbSqlCompletionMetadataRequest request) {
        if (request == null || context == null || converter == null || context.getMetaData() == null) {
            return SqlCompletionMetadataResponse.unsupported();
        }
        SqlCompletionCandidateTypeEnum type = SqlCompletionCandidateTypeEnum.from(request.type());
        if (!supports(request.type())) {
            return SqlCompletionMetadataResponse.unsupported();
        }
        List<SqlCompletionCandidate> candidates = switch (type) {
            case CATALOG, DATABASE -> listDatabases();
            case SCHEMA -> listSchemas(resolveDatabaseName(request.scope()));
            case TABLE -> listTables(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()));
            case VIEW -> listViews(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()));
            case TABLE_VIEW -> listTableViews(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()));
            case COLUMN -> listColumns(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()),
                    resolveTableName(request.scope()));
            case FUNCTION -> listFunctions(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()));
            case PROCEDURE -> listProcedures(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()));
            case TRIGGER -> listTriggers(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()));
            case EVENT -> listEvents(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()));
            case PARAMETER -> listRoutineParameters(request);
            default -> List.of();
        };
        return SqlCompletionMetadataResponse.of(filterByPrefix(candidates, request.prefix()));
    }

    @Override
    public boolean supports(String typeValue) {
        SqlCompletionCandidateTypeEnum type = SqlCompletionCandidateTypeEnum.from(typeValue);
        return type == SqlCompletionCandidateTypeEnum.CATALOG
                || type == SqlCompletionCandidateTypeEnum.DATABASE
                || type == SqlCompletionCandidateTypeEnum.SCHEMA
                || type == SqlCompletionCandidateTypeEnum.TABLE
                || type == SqlCompletionCandidateTypeEnum.VIEW
                || type == SqlCompletionCandidateTypeEnum.TABLE_VIEW
                || type == SqlCompletionCandidateTypeEnum.COLUMN
                || type == SqlCompletionCandidateTypeEnum.FUNCTION
                || type == SqlCompletionCandidateTypeEnum.PROCEDURE
                || type == SqlCompletionCandidateTypeEnum.TRIGGER
                || type == SqlCompletionCandidateTypeEnum.EVENT
                || type == SqlCompletionCandidateTypeEnum.PARAMETER;
    }

    private List<SqlCompletionCandidate> listDatabases() {
        List<Database> databases = metaData().databases(connection());
        return converter.databases2candidates(databases, context.getDatasourceName(), identifierProcessor());
    }

    private List<SqlCompletionCandidate> listSchemas(String databaseName) {
        List<Schema> schemas = metaData().schemas(connection(), databaseName);
        return converter.schemas2candidates(schemas, databaseName, context.getDatasourceName(), identifierProcessor());
    }

    private List<SqlCompletionCandidate> listTables(String databaseName, String schemaName) {
        String tableKey = getTableKey(context.getDataSourceId(), databaseName, schemaName);
        List<Table> tables = MemoryCacheManage.computeIfAbsent(tableKey,
                () -> new ArrayList<>(metaData().tables(connection(), new TablesRequest(databaseName, schemaName, null))));
        return converter.tables2candidates(tables, databaseName, schemaName, context.getDatasourceName(),
                identifierProcessor());
    }

    private List<SqlCompletionCandidate> listViews(String databaseName, String schemaName) {
        List<Table> views = metaData().views(connection(), databaseName, schemaName);
        return converter.views2candidates(views, databaseName, schemaName, context.getDatasourceName(),
                identifierProcessor());
    }

    private List<SqlCompletionCandidate> listTableViews(String databaseName, String schemaName) {
        List<SqlCompletionCandidate> candidates = new ArrayList<>();
        candidates.addAll(listTables(databaseName, schemaName));
        candidates.addAll(listViews(databaseName, schemaName));
        return candidates;
    }

    private List<SqlCompletionCandidate> listColumns(String databaseName, String schemaName, String tableName) {
        if (StringUtils.isBlank(tableName)) {
            return List.of();
        }
        String columnKey = getColumnKey(context.getDataSourceId(), databaseName, schemaName, tableName);
        List<TableColumn> columns = MemoryCacheManage.computeIfAbsent(columnKey,
                () -> new ArrayList<>(metaData().columns(connection(), new TableMetadataRequest(databaseName, schemaName, tableName))));
        return converter.columns2candidates(columns, databaseName, schemaName, tableName, context.getDatasourceName(),
                identifierProcessor());
    }

    private List<SqlCompletionCandidate> listFunctions(String databaseName, String schemaName) {
        List<Function> functions = metaData().functions(connection(), databaseName, schemaName);
        return converter.functions2candidates(functions, databaseName, schemaName, context.getDatasourceName(),
                identifierProcessor());
    }

    private List<SqlCompletionCandidate> listProcedures(String databaseName, String schemaName) {
        List<Procedure> procedures = metaData().procedures(connection(), databaseName, schemaName);
        return converter.procedures2candidates(procedures, databaseName, schemaName, context.getDatasourceName(),
                identifierProcessor());
    }

    private List<SqlCompletionCandidate> listRoutineParameters(DbSqlCompletionMetadataRequest request) {
        if (request == null || request.scope() == null || StringUtils.isBlank(request.scope().object())) {
            return List.of();
        }
        List<SqlCompletionCandidate> candidates;
        SqlCompletionCandidateTypeEnum objectType = SqlCompletionCandidateTypeEnum.from(request.objectType());
        if (objectType == SqlCompletionCandidateTypeEnum.PROCEDURE) {
            candidates = listProcedureParameters(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()),
                    request.scope().object());
        } else if (objectType == SqlCompletionCandidateTypeEnum.FUNCTION) {
            candidates = listFunctionParameters(resolveDatabaseName(request.scope()), resolveSchemaName(request.scope()),
                    request.scope().object());
        } else {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> candidate != null && StringUtils.isNotBlank(candidate.getLabel()))
                .filter(candidate -> candidate.getSortRank() == null || candidate.getSortRank() > 0)
                .sorted(Comparator.comparingInt(left -> left.getSortRank() == null ? Integer.MAX_VALUE : left.getSortRank()))
                .toList();
    }

    private List<SqlCompletionCandidate> listFunctionParameters(String databaseName, String schemaName,
                                                                String functionName) {
        List<FunctionParameter> parameters = metaData().getFunctionParameters(connection(),
                new FunctionMetadataRequest(StringUtils.defaultIfBlank(databaseName, context.getDatabaseName()),
                        StringUtils.defaultIfBlank(schemaName, context.getSchemaName()),
                        functionName));
        return converter.functionParameters2candidates(parameters);
    }

    private List<SqlCompletionCandidate> listProcedureParameters(String databaseName, String schemaName,
                                                                 String procedureName) {
        List<ProcedureParameter> parameters = metaData().getProcedureParameters(connection(),
                new ProcedureMetadataRequest(StringUtils.defaultIfBlank(databaseName, context.getDatabaseName()),
                        StringUtils.defaultIfBlank(schemaName, context.getSchemaName()),
                        procedureName));
        return converter.procedureParameters2candidates(parameters);
    }

    private List<SqlCompletionCandidate> listTriggers(String databaseName, String schemaName) {
        List<Trigger> triggers = metaData().triggers(connection(), databaseName, schemaName);
        return converter.triggers2candidates(triggers, databaseName, schemaName, context.getDatasourceName(),
                identifierProcessor());
    }

    private List<SqlCompletionCandidate> listEvents(String databaseName, String schemaName) {
        List<ai.chat2db.community.domain.api.model.metadata.Event> events =
                metaData().events(connection(), databaseName, schemaName);
        return converter.events2candidates(events, databaseName, schemaName, context.getDatasourceName(),
                identifierProcessor());
    }

    private List<SqlCompletionCandidate> filterByPrefix(List<SqlCompletionCandidate> candidates, String prefix) {
        if (StringUtils.isBlank(prefix)) {
            return candidates == null ? List.of() : candidates;
        }
        String normalizedPrefix = prefix.trim();
        return candidates == null ? List.of()
                : candidates.stream()
                .filter(candidate -> StringUtils.startsWithIgnoreCase(candidate.getLabel(), normalizedPrefix))
                .toList();
    }

    private String resolveDatabaseName(SqlCompletionMetadataScope scope) {
        if (scope != null && StringUtils.isNotBlank(scope.catalog())) {
            return scope.catalog();
        }
        if (mysql() && scope != null && StringUtils.isNotBlank(scope.schema())) {
            return scope.schema();
        }
        return context.getDatabaseName();
    }

    private String resolveSchemaName(SqlCompletionMetadataScope scope) {
        if (mysql() && scope != null && StringUtils.isNotBlank(scope.schema())) {
            return null;
        }
        if (scope != null && StringUtils.isNotBlank(scope.schema())) {
            return scope.schema();
        }
        return context.getSchemaName();
    }

    private String resolveTableName(SqlCompletionMetadataScope scope) {
        if (scope != null && StringUtils.isNotBlank(scope.table())) {
            return scope.table();
        }
        return null;
    }

    private IDbMetaData metaData() {
        return context.getMetaData();
    }

    private Connection connection() {
        return context.getConnectionSupplier() == null ? null : context.getConnectionSupplier().get();
    }

    private ISQLIdentifierProcessor identifierProcessor() {
        return context.getIdentifierProcessor();
    }

    private boolean mysql() {
        return context.getDbConfig() != null
                && StringUtils.equalsIgnoreCase(DatabaseTypeEnum.MYSQL.name(), context.getDbConfig().getDbType());
    }
}
