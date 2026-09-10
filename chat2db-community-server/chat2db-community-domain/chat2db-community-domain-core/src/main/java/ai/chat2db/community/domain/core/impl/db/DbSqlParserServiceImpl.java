package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.spi.DefaultSqlSyntaxHandler;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.IdentifierTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.StatementValidTypeEnum;
import ai.chat2db.community.domain.api.model.parser.message.SyntaxErrorMessage;
import ai.chat2db.community.domain.api.model.parser.result.SqlParserResponse;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.insert.InsertValueMapping;
import ai.chat2db.community.domain.api.enums.parser.InsertValueMappingStatusEnum;
import ai.chat2db.community.domain.api.model.parser.token.Identifier;
import ai.chat2db.community.domain.api.model.parser.token.IntervalToken;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.community.domain.api.model.console.ConsoleParserCache;
import ai.chat2db.community.domain.api.model.sql.MarkMessage;
import ai.chat2db.community.domain.api.model.db.SimpleColumn;
import ai.chat2db.community.domain.api.model.db.SimpleDatabase;
import ai.chat2db.community.domain.api.model.db.SimpleFunction;
import ai.chat2db.community.domain.api.model.db.SimpleIdentifier;
import ai.chat2db.community.domain.api.model.db.SimpleInsertValueMapping;
import ai.chat2db.community.domain.api.model.db.SimpleProcedure;
import ai.chat2db.community.domain.api.model.db.SimpleSchema;
import ai.chat2db.community.domain.api.model.db.SimpleTable;
import ai.chat2db.community.domain.api.model.db.SimpleTableColumnMapping;
import ai.chat2db.community.domain.api.model.db.SimpleView;
import ai.chat2db.community.domain.api.model.sql.SqlContextParser;
import ai.chat2db.community.domain.api.model.sql.SqlHover;
import ai.chat2db.community.domain.api.model.sql.SqlKeyword;
import ai.chat2db.community.domain.api.model.sql.SqlStatement;
import ai.chat2db.community.domain.api.model.request.db.DbMetaDataQueryRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlContextParserRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlHoverRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlKeywordRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbDatabaseService;
import ai.chat2db.community.domain.api.service.db.IDbSqlParserService;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.domain.core.cache.CacheManage;
import ai.chat2db.community.domain.core.cache.MemoryCacheManage;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.ColumnMetadataRequest;
import ai.chat2db.spi.model.request.FunctionMetadataRequest;
import ai.chat2db.spi.model.request.ProcedureMetadataRequest;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.model.request.TriggerMetadataRequest;
import ai.chat2db.spi.model.request.ViewMetadataRequest;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.Token;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.community.domain.core.cache.CacheKey.*;


@Service
@Slf4j
public class DbSqlParserServiceImpl implements IDbSqlParserService {

    private static final Set<String> SUPPORT_SYNTAX_CHECK_DB = Set.of(DatabaseTypeEnum.MYSQL.name(),
            DatabaseTypeEnum.POSTGRESQL.name(),
            DatabaseTypeEnum.ORACLE.name(),
            DatabaseTypeEnum.OSCAR.name(),
            DatabaseTypeEnum.SQLSERVER.name(),
            DatabaseTypeEnum.MARIADB.name());
    @Autowired
    private IDbDatabaseService databaseService;

    @Autowired
    private IDbTableService tableService;

    @Override
    public SqlKeyword getKeywords(DbSqlKeywordRequest sqlKeywordParam) {
        try {
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            String dbType = connectInfo.getDbType();
            if (StringUtils.equalsAnyIgnoreCase(dbType,
                    DatabaseTypeEnum.MONGODB.name(),
                    DatabaseTypeEnum.REDIS.name())) {
                return null;
            }
            String databaseSourceName = connectInfo.getAlias();
            Long dataSourceId = sqlKeywordParam.getDataSourceId();
            SqlKeyword sqlKeyword = new SqlKeyword();
            IDbMetaData metaData = Chat2DBContext.getDbMetaData();
            ISQLIdentifierProcessor sqlIdentifierProcessor = metaData.getSQLIdentifierProcessor();
            DBConfig dbConfig = Chat2DBContext.getDBConfig();
            String databaseName = sqlKeywordParam.getDatabaseName();
            String schemaName = sqlKeywordParam.getSchemaName();
            boolean supportDatabase = dbConfig.isSupportDatabase();
            boolean supportSchema = dbConfig.isSupportSchema();
            Connection connection = Chat2DBContext.getConnection();
            Try.run(() -> {
                DbMetaDataQueryRequest metaDataQueryParam = new DbMetaDataQueryRequest();
                metaDataQueryParam.setDataSourceId(dataSourceId);
                metaDataQueryParam.setRefresh(false);
                MetaSchema data = databaseService.queryDatabaseSchema(metaDataQueryParam);
                List<Database> databases;
                List<Schema> schemas;
                if (Objects.nonNull(data)) {
                    if (supportDatabase) {
                        databases = data.getDatabases();
                        if (CollectionUtils.isEmpty(databases)) {
                            databases = metaData.databases(connection);
                        }
                        List<SimpleDatabase> simpleDatabases = databases.stream().map(database -> {
                            SimpleDatabase simpleDatabase = new SimpleDatabase();
                            String name = database.getName();
                            if (supportSchema
                                    && StringUtils.isNotBlank(databaseName)
                                    && Objects.equals(name, databaseName)) {
                                List<Schema> databaseSchemas = database.getSchemas();
                                if (CollectionUtils.isEmpty(databaseSchemas)) {
                                    fetchAndSetSchemas(sqlKeyword, connection, dbType, sqlIdentifierProcessor, name, metaData, databaseSourceName);
                                } else {
                                    setSchemas(sqlKeyword, databaseSchemas, dbType, sqlIdentifierProcessor, databaseName, databaseSourceName);
                                }
                            }
                            simpleDatabase.setDatabaseName(name);
                            simpleDatabase.setInsertText(sqlIdentifierProcessor.quoteIdentifier(name));
                            simpleDatabase.setDatasourceName(databaseSourceName);
                            return simpleDatabase;
                        }).toList();
                        sqlKeyword.setDatabases(simpleDatabases);
                    } else if (supportSchema && CollectionUtils.isEmpty(sqlKeyword.getSchemas())) {
                        schemas = data.getSchemas();
                        if (CollectionUtils.isNotEmpty(schemas)) {
                            setSchemas(sqlKeyword, schemas, dbType, sqlIdentifierProcessor, databaseName, databaseSourceName);
                        } else {
                            fetchAndSetSchemas(sqlKeyword, connection, dbType, sqlIdentifierProcessor, databaseName, metaData, databaseSourceName);
                        }
                    }
                } else {
                    if (supportDatabase) {
                        fetchAndSetDatabases(sqlKeyword, connection, metaData, databaseSourceName);
                    }
                    if (supportSchema && StringUtils.isNotBlank(databaseName)) {
                        fetchAndSetSchemas(sqlKeyword, connection, dbType, sqlIdentifierProcessor, databaseName, metaData, databaseSourceName);
                    }
                }

            }).onFailure(e -> log.error("get databases or schemas error", e));


            if (supportDatabase && StringUtils.isBlank(databaseName)) {
                sqlKeyword.setViews(List.of());
                sqlKeyword.setTables(List.of());
                sqlKeyword.setSchemas(List.of());
                sqlKeyword.setFunctions(List.of());
                sqlKeyword.setProcedures(List.of());
                return sqlKeyword;
            }

            if (supportSchema && StringUtils.isBlank(schemaName)) {
                sqlKeyword.setViews(List.of());
                sqlKeyword.setFunctions(List.of());
                sqlKeyword.setTables(List.of());
                sqlKeyword.setProcedures(List.of());
                return sqlKeyword;

            }

            Try.run(() -> {
                String key = getTableKey(dataSourceId, databaseName, schemaName);
                List<Table> all = MemoryCacheManage.computeIfAbsent(key, () -> {
                    List<Table> tables = metaData.tables(connection, new TablesRequest(databaseName, schemaName, null));
                    return new ArrayList<>(tables);
                });
                if (CollectionUtils.isNotEmpty(all)) {
                    List<SimpleTable> simpleTables = all.stream().map(table -> {
                        String insertText = sqlIdentifierProcessor.quoteIdentifier(table.getName());
                        SimpleTable simpleTable = new SimpleTable(table, insertText);
                        simpleTable.setDatasourceName(databaseSourceName);
                        return simpleTable;
                    }).toList();
                    if (CollectionUtils.isNotEmpty(simpleTables)) {
                        sqlKeyword.setTables(simpleTables);
                    } else {
                        sqlKeyword.setTables(List.of());
                    }
                }

            }).onFailure(e -> log.error("get tables or columns error", e));

            Try.run(() -> {
                List<Table> views = metaData.views(connection, databaseName, schemaName);
                if (CollectionUtils.isNotEmpty(views)) {
                    List<SimpleView> simpleViews = views.stream().map(view -> {
                        String insertText = sqlIdentifierProcessor.quoteIdentifier(view.getName());
                        SimpleView simpleView = new SimpleView(view, insertText);
                        simpleView.setDatasourceName(databaseSourceName);
                        return simpleView;
                    }).toList();
                    if (CollectionUtils.isNotEmpty(simpleViews)) {
                        sqlKeyword.setViews(simpleViews);
                    }
                } else {
                    sqlKeyword.setViews(List.of());
                }
            }).onFailure(e -> log.error("get views error", e));
            Try.run(() -> {
                List<SimpleFunction> simpleFunctions = new ArrayList<>();
                for (Function function : metaData.functions(connection, databaseName, schemaName)) {
                    SimpleFunction simpleFunction = new SimpleFunction();
                    String functionName = function.getFunctionName();
                    simpleFunction.setFunctionName(functionName);
                    simpleFunction.setInsertText(sqlIdentifierProcessor.quoteIdentifier(functionName));
                    simpleFunction.setDatasourceName(databaseSourceName);
                    simpleFunction.setSchemaName(schemaName);
                    simpleFunction.setDatabaseName(databaseName);
                    simpleFunction.setParameters(List.of());
                    simpleFunctions.add(simpleFunction);

                }
                if (CollectionUtils.isNotEmpty(simpleFunctions)) {
                    sqlKeyword.setFunctions(simpleFunctions);
                } else {
                    sqlKeyword.setFunctions(List.of());
                }
            }).onFailure(e -> log.error("get functions error", e));

            Try.run(() -> {
                List<SimpleProcedure> simpleProcedures = new ArrayList<>();
                for (Procedure procedure : metaData.procedures(connection, databaseName, schemaName)) {
                    SimpleProcedure simpleProcedure = new SimpleProcedure();
                    String procedureName = procedure.getProcedureName();
                    simpleProcedure.setProcedureName(procedureName);
                    simpleProcedure.setInsertText(sqlIdentifierProcessor.quoteIdentifier(procedureName));
                    simpleProcedure.setDatasourceName(databaseSourceName);
                    simpleProcedure.setSchemaName(schemaName);
                    simpleProcedure.setDatabaseName(databaseName);
                    simpleProcedure.setParameters(List.of());
                    simpleProcedures.add(simpleProcedure);
                }
                if (CollectionUtils.isNotEmpty(simpleProcedures)) {
                    sqlKeyword.setProcedures(simpleProcedures);
                } else {
                    sqlKeyword.setProcedures(List.of());
                }
            }).onFailure(e -> log.error("get procedures error", e));


            return sqlKeyword;
        } catch (Exception e) { // impl-contract: fallback - keyword metadata failure leaves editor suggestions empty.
            log.error("get keywords error", e);
            SqlKeyword fallback = new SqlKeyword();
            fallback.setDatabases(List.of());
            fallback.setSchemas(List.of());
            fallback.setTables(List.of());
            fallback.setViews(List.of());
            fallback.setFunctions(List.of());
            fallback.setProcedures(List.of());
            return fallback;
        }
    }

    @Override
    public SqlContextParser contextParser(DbSqlContextParserRequest sqlContextParserParam) {
        String sql = sqlContextParserParam.getSql();
        if (StringUtils.isBlank(sql) || sql.length() > 50000) {
            return null;
        }
        try {
            SqlContextParser sqlContextParser = new SqlContextParser();
            String dbType = Chat2DBContext.getDBConfig().getDbType();
            if (StringUtils.equalsAny(dbType.toUpperCase(),
                    DatabaseTypeEnum.REDIS.name(),
                    DatabaseTypeEnum.MONGODB.name())) {
                return null;
            }
            ISQLParser sqlParser = DefaultSqlSyntaxHandler.getSQLParser(dbType.toUpperCase());
            if (Objects.isNull(sqlParser)) {
                return null;
            }
            String databaseName = sqlContextParserParam.getDatabaseName();
            String schemaName = sqlContextParserParam.getSchemaName();
            Long consoleId = sqlContextParserParam.getConsoleId();
            Long dataSourceId = sqlContextParserParam.getDataSourceId();
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            DBConfig dbConfig = Chat2DBContext.getDBConfig();
            boolean supportDatabase = dbConfig.isSupportDatabase();
            boolean supportSchema = dbConfig.isSupportSchema();
            boolean configOk = true;
            if (supportDatabase && StringUtils.isBlank(databaseName)) {
                configOk = false;
            }
            if (supportSchema && StringUtils.isBlank(schemaName)) {
                configOk = false;
            }
            String datasourceName = connectInfo.getAlias();
            IDbMetaData metaData = Chat2DBContext.getDbMetaData();
            ISQLIdentifierProcessor sqlIdentifierProcessor = metaData.getSQLIdentifierProcessor();
            SqlParserResponse sqlParserResult = sqlParser.parserStatements(sql);
            List<Statement> statements = sqlParserResult.getStatements();
            String consoleParserKey = getConsoleParserKey(dataSourceId, consoleId);
            if (CollectionUtils.isEmpty(statements)) {
                MemoryCacheManage.remove(consoleParserKey);
                return null;
            }
            List<SqlStatement> sqlStatements = new ArrayList<>();

            ConsoleParserCache consoleParserCache = MemoryCacheManage.computeIfAbsent(consoleParserKey, ConsoleParserCache::new);
            consoleParserCache.addDataSource(datasourceName, String.valueOf(dataSourceId));
            List<Table> allTables;
            Map<String, Table> tableMap = new HashMap<>();
            if (configOk) {
                String tableKey = getTableKey(dataSourceId, databaseName, schemaName);
                allTables = MemoryCacheManage.computeIfAbsent(tableKey, () -> {
                    List<Table> tables = metaData.tables(Chat2DBContext.getConnection(),
                            new TablesRequest(databaseName, schemaName, null));
                    return new ArrayList<>(tables);
                });
                tableMap = toTableMap(allTables);
            }

            for (Statement statement : statements) {
                String sqlType = statement.getType();
                if (!SUPPORT_SYNTAX_CHECK_DB.contains(dbType.toUpperCase())
                        && !SqlTypeEnum.SELECT.name().equals(sqlType)) {
                    continue;
                }
                SqlStatement sqlStatement = new SqlStatement();
                String statementType = statement.getStatementType();
                String statementSql = statement.getSql();
                sqlStatement.setSql(statementSql);
                sqlStatement.setType(sqlType);
                Token statementFirstToken = statement.getFirstToken();
                Token statementLastToken = statement.getLastToken();
                if (Objects.nonNull(statementFirstToken)) {
                    sqlStatement.setSqlStartRowNum(statementFirstToken.getLine());
                    sqlStatement.setSqlStartColNum(statementFirstToken.getCharPositionInLine() + 1);
                }
                if (Objects.nonNull(statementLastToken)) {
                    sqlStatement.setSqlEndRowNum(statementLastToken.getLine());
                    sqlStatement.setSqlEndColNum(statementLastToken.getCharPositionInLine()
                            + statementLastToken.getText().length() + 1);
                }
                sqlStatement.setComment(statement.getComment());
                sqlStatement.setStatementType(statementType);
                sqlStatement.setInsertValueMappings(getSimpleInsertValueMappings(statement.getInsertValueMappings()));
                List<Identifier> identifiers = statement.getIdentifiers();
                Map<String, SimpleTableColumnMapping> simpleTableColumnMappings = new HashMap<>(identifiers.size());
                if (StatementValidTypeEnum.INVALID.name().equals(statementType)) {
                    List<Token> tokensOnDefault = DefaultSqlSyntaxHandler.getTokensOnDefault(statementSql, dbType);
                    if (CollectionUtils.isEmpty(tokensOnDefault) || MapUtils.isEmpty(tableMap)) {
                        continue;
                    }
                    for (Token token : tokensOnDefault) {
                        String text = token.getText();
                        text = normalizeIdentifierToken(sqlIdentifierProcessor, text);
                        if (tableMap.containsKey(text)) {
                            SimpleTableColumnMapping simpleTableColumnMapping = new SimpleTableColumnMapping();
                            Table table = tableMap.get(text);
                            SimpleTable simpleTable = new SimpleTable(table);
                            simpleTable.setDatasourceName(datasourceName);
                            simpleTableColumnMapping.setSimpleTable(simpleTable);
                            String columnKey = getColumnKey(dataSourceId, databaseName, schemaName, text);
                            String finalText = text;
                            List<TableColumn> tableColumns = CacheManage.getList(columnKey, TableColumn.class,
                                    (key) -> false, (key) ->
                                            metaData.columns(Chat2DBContext.getConnection(),
                                                    new TableMetadataRequest(databaseName, schemaName, finalText)));
                            if (CollectionUtils.isNotEmpty(tableColumns)) {
                                List<SimpleColumn> simpleColumns = getSimpleColumns(tableColumns, datasourceName,
                                        databaseName, schemaName, finalText);
                                for (SimpleColumn simpleColumn : simpleColumns) {
                                     simpleColumn.setInsertText(sqlIdentifierProcessor.quoteIdentifier(simpleColumn.getColumnName()));
                                }
                                simpleTableColumnMapping.setSimpleColumns(simpleColumns);
                            }

                            if (!simpleTableColumnMappings.containsKey(table.getName())) {
                                simpleTableColumnMappings.put(table.getName(), simpleTableColumnMapping);
                            }
                        }
                    }
                }
                if (CollectionUtils.isNotEmpty(identifiers)) {
                    List<SimpleIdentifier> simpleIdentifiers = new ArrayList<>(identifiers.size());
                    for (Identifier identifier : identifiers) {
                        SimpleIdentifier simpleIdentifier = new SimpleIdentifier();
                        String identifierType = identifier.getIdentifierType();
                        String identifierTable = identifier.getIdentifierTable();
                        String identifierName = identifier.getIdentifierName();
                        // Effectively-final so the values can be captured by the column-loader
                        // lambda below; fall back to the console's db/schema only when the
                        // identifier does not itself carry one.
                        String rawIdentifierDatabase = identifier.getIdentifierDatabase();
                        String identifierDatabase = StringUtils.isBlank(rawIdentifierDatabase)
                                && !StringUtils.equals(IdentifierTypeEnum.DATABASE.name(), identifierType)
                                ? databaseName : rawIdentifierDatabase;
                        String rawIdentifierSchema = identifier.getIdentifierSchema();
                        String identifierSchema = StringUtils.isBlank(rawIdentifierSchema)
                                && !StringUtils.equals(IdentifierTypeEnum.SCHEMA.name(), identifierType)
                                ? schemaName : rawIdentifierSchema;
                        String identifierAlias = identifier.getIdentifierAlias();
                        simpleIdentifier.setName(identifierName);
                        simpleIdentifier.setAlias(identifierAlias);
                        simpleIdentifier.setType(identifierType);
                        simpleIdentifier.setIdentifierDatabase(identifierDatabase);
                        simpleIdentifier.setIdentifierSchema(identifierSchema);
                        simpleIdentifier.setIdentifierTable(identifierTable);
                        Token identifierFirstToken = identifier.getFirstToken();
                        Token identifierLastToken = identifier.getLastToken();
                        if (Objects.nonNull(identifierFirstToken)) {
                            simpleIdentifier.setIdentifierStartRowNum(identifierFirstToken.getLine());
                            simpleIdentifier.setIdentifierStartColNum(identifierFirstToken.getCharPositionInLine() + 1);
                            if (identifierFirstToken instanceof IntervalToken intervalToken) {
                                simpleIdentifier.setIdentifierEndRowNum(intervalToken.getEndLine());
                                simpleIdentifier.setIdentifierEndColNum(intervalToken.getEndColumn() + 1);
                            } else {
                                simpleIdentifier.setIdentifierEndRowNum(identifierFirstToken.getLine());
                                simpleIdentifier.setIdentifierEndColNum(identifierFirstToken.getCharPositionInLine()
                                        + identifierFirstToken.getText().length() + 1);
                            }
                        }
                        if (Objects.nonNull(identifierLastToken) && StringUtils.isNotBlank(identifierAlias)) {
                            simpleIdentifier.setAliasStartColNum(identifierLastToken.getCharPositionInLine() + 1);
                            simpleIdentifier.setAliasEndColNum(identifierLastToken.getCharPositionInLine()
                                    + identifierLastToken.getText().length() + 1);
                            simpleIdentifier.setAliasStartRowNum(identifierLastToken.getLine());
                            simpleIdentifier.setAliasEndRowNum(identifierLastToken.getLine());
                        }

                        if (IdentifierTypeEnum.TABLE.name().equals(identifier.getIdentifierType())) {
                            if (supportDatabase && StringUtils.isBlank(identifierDatabase)) {
                                continue;
                            }
                            if (supportSchema && StringUtils.isBlank(identifierSchema)) {
                                continue;
                            }
                            if (tableMap.containsKey(identifierName)) {
                                consoleParserCache.addTable(String.valueOf(dataSourceId), identifierDatabase,
                                        identifierSchema, identifierName, identifierAlias,
                                        supportDatabase, supportSchema);
                                if (!simpleTableColumnMappings.containsKey(identifierName)) {
                                    SimpleTable simpleTable = new SimpleTable(tableMap.get(identifierName));
                                    simpleTable.setTableAlias(identifierAlias);
                                    String tableColumnKey = getColumnKey(dataSourceId, identifierDatabase, identifierSchema, identifierName);
                                    List<TableColumn> tableColumnList =
                                            CacheManage.getList(tableColumnKey, TableColumn.class,
                                                    (key) -> false, (key) -> metaData.columns(Chat2DBContext.getConnection(),
                                                            // Load columns for the identifier's own db/schema (the values
                                                            // used in the cache key), not the request-level db/schema, so a
                                                            // cross-database/cross-schema table reference does not poison the
                                                            // cache with columns from the console's db/schema.
                                                            new TableMetadataRequest(identifierDatabase, identifierSchema, identifierName)));

                                    List<SimpleColumn> simpleColumns = getSimpleColumns(tableColumnList, datasourceName,
                                            identifierDatabase, identifierSchema, identifierName);
                                    for (SimpleColumn simpleColumn : simpleColumns) {
                                        simpleColumn.setInsertText(sqlIdentifierProcessor.quoteIdentifier(simpleColumn.getColumnName()));
                                    }
                                    SimpleTableColumnMapping simpleTableColumnMapping = new SimpleTableColumnMapping();
                                    simpleTableColumnMapping.setSimpleTable(simpleTable);
                                    simpleTableColumnMapping.setSimpleColumns(simpleColumns);
                                    simpleTableColumnMappings.put(identifierName, simpleTableColumnMapping);
                                }
                            }


                        }
                        simpleIdentifiers.add(simpleIdentifier);
                    }
                    sqlStatement.setIdentifiers(simpleIdentifiers);
                } else {
                    sqlStatement.setIdentifiers(List.of());
                }
                sqlStatement.setTableColumns(simpleTableColumnMappings.values().stream().toList());
                sqlStatements.add(sqlStatement);
            }

            sqlContextParser.setSqlStatementList(sqlStatements);
            sqlContextParser.setMarkMessageList(List.of());
            if (SUPPORT_SYNTAX_CHECK_DB.contains(dbType.toUpperCase())) {
                List<SyntaxErrorMessage> syntaxErrors = sqlParserResult.getSyntaxErrors();
                List<MarkMessage> errorMessageVos = syntaxErrors.stream().map(MarkMessage::new).toList();
                if (CollectionUtils.isNotEmpty(errorMessageVos)) {
                    sqlContextParser.setMarkMessageList(errorMessageVos);
                }
            }
            MemoryCacheManage.put(consoleParserKey, consoleParserCache);

            return sqlContextParser;
        } catch (Exception e) { // impl-contract: fallback - parser failure disables editor context hints for this SQL.
            log.error("sql parser error ", e);
            SqlContextParser fallback = new SqlContextParser();
            fallback.setSqlStatementList(List.of());
            fallback.setMarkMessageList(List.of());
            return fallback;
        }
    }

    static String normalizeIdentifierToken(ISQLIdentifierProcessor processor, String tokenText) {
        if (tokenText == null) {
            return null;
        }
        String identifier = tokenText.startsWith(".") ? tokenText.substring(1) : tokenText;
        return processor.removeIdentifierQuote(identifier);
    }

    static Map<String, Table> toTableMap(List<Table> tables) {
        return tables.stream().collect(Collectors.toMap(Table::getName, table -> table, (first, ignored) -> first));
    }

    @Override
    public List<SqlHover> sqlHover(DbSqlHoverRequest sqlHoverParam) {
        try {
            ArrayList<SqlHover> sqlHovers = new ArrayList<>();
            SqlHover sqlHover = new SqlHover();
            SimpleIdentifier hoverIdentifier = sqlHoverParam.getHoverIdentifier();
            Long datasourceId = sqlHoverParam.getDataSourceId();
            String hoverType = hoverIdentifier.getType();
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            String datasourceName = connectInfo.getAlias();
            sqlHover.setDatasourceName(datasourceName);
            if (StringUtils.isBlank(hoverType)) {
                return List.of();
            }
            String hoverText = hoverIdentifier.getName();
            String databaseName = sqlHoverParam.getDatabaseName();
            if (StringUtils.isNotBlank(hoverIdentifier.getIdentifierDatabase())) {
                databaseName = hoverIdentifier.getIdentifierDatabase();
            }
            String schemaName = sqlHoverParam.getSchemaName();
            if (StringUtils.isNotBlank(hoverIdentifier.getIdentifierSchema())) {
                schemaName = hoverIdentifier.getIdentifierSchema();
            }
            String tableName = hoverIdentifier.getIdentifierTable();
            DBConfig dbConfig = Chat2DBContext.getDBConfig();
            boolean supportDatabase = dbConfig.isSupportDatabase();
            boolean supportSchema = dbConfig.isSupportSchema();
            if (supportDatabase && StringUtils.isBlank(databaseName)) {
                return List.of();
            }
            if (supportSchema && StringUtils.isBlank(schemaName)) {
                return List.of();
            }
            IDbMetaData metaData = Chat2DBContext.getDbMetaData();
            Connection connection = Chat2DBContext.getConnection();

            if (StringUtils.equals(IdentifierTypeEnum.DATABASE.name(),
                    hoverType.toUpperCase())) {
                DbMetaDataQueryRequest metaDataQueryParam = new DbMetaDataQueryRequest();
                metaDataQueryParam.setDataSourceId(datasourceId);
                metaDataQueryParam.setRefresh(false);
                MetaSchema data = databaseService.queryDatabaseSchema(metaDataQueryParam);
                if (Objects.isNull(data)) {
                    return List.of();
                }
                if (Objects.isNull(data)) {
                    return List.of();
                }

                List<Database> databases = data.getDatabases();
                for (Database database : databases) {
                    if (StringUtils.equals(database.getName(), databaseName)) {
                        sqlHover.setDatasourceName(datasourceName);
                        sqlHover.setDatabaseName(database.getName());
                        sqlHovers.add(sqlHover);
                        return sqlHovers;
                    }
                }

            } else if (Objects.equals(IdentifierTypeEnum.SCHEMA.name(), hoverType.toUpperCase())) {
                DbMetaDataQueryRequest metaDataQueryParam = new DbMetaDataQueryRequest();
                metaDataQueryParam.setDataSourceId(datasourceId);
                metaDataQueryParam.setRefresh(false);
                MetaSchema data = databaseService.queryDatabaseSchema(metaDataQueryParam);
                if (Objects.isNull(data)) {
                    return List.of();
                }

                if (Objects.isNull(data)) {
                    return List.of();
                }

                List<Database> databases = data.getDatabases();
                for (Database database : databases) {
                    if (!StringUtils.equals(database.getName(), databaseName)) {
                        continue;
                    }
                    for (Schema schema : database.getSchemas()) {
                        if (StringUtils.equals(schema.getName(), hoverText)) {
                            sqlHover.setDatabaseName(database.getName());
                            sqlHover.setComment(schema.getComment());
                            sqlHover.setSchemaName(schema.getName());
                            sqlHovers.add(sqlHover);
                            return sqlHovers;
                        }
                    }
                }
                for (Schema schema : data.getSchemas()) {
                    if (StringUtils.equals(schema.getName(), hoverText)) {
                        sqlHover.setDatabaseName(databaseName);
                        sqlHover.setComment(schema.getComment());
                        sqlHover.setSchemaName(schema.getName());
                        sqlHovers.add(sqlHover);
                        return sqlHovers;
                    }
                }
            } else if (Objects.equals(IdentifierTypeEnum.TABLE.name(), hoverType.toUpperCase())) {
                String tableKey = getTableKey(datasourceId, databaseName, schemaName);
                List<Table> tableList;
                tableList = MemoryCacheManage.get(tableKey);
                if (CollectionUtils.isEmpty(tableList)) {
                    tableList = metaData.tables(connection, new TablesRequest(databaseName, schemaName, null));
                }
                Table table = metaData.getTable(tableList, hoverText);
                if (Objects.nonNull(table)) {
                    String tableDDL = metaData.tableDDL(connection,
                            new TableMetadataRequest(databaseName, schemaName, table.getName()));
                    if (StringUtils.isNotBlank(tableDDL)) {
                        sqlHover.setDatabaseName(databaseName);
                        sqlHover.setSchemaName(schemaName);
                        sqlHover.setDdl(tableDDL);
                        sqlHover.setComment(table.getComment());
                        sqlHovers.add(sqlHover);
                        return sqlHovers;
                    }
                } else {
                    Table view = metaData.view(connection, new ViewMetadataRequest(databaseName, schemaName, hoverText));
                    if (Objects.nonNull(view)) {
                        sqlHover.setDatabaseName(databaseName);
                        sqlHover.setSchemaName(schemaName);
                        sqlHover.setComment(view.getComment());
                        sqlHover.setDdl(view.getDdl());
                        sqlHovers.add(sqlHover);
                        return sqlHovers;
                    }
                }
            } else if (Objects.equals(IdentifierTypeEnum.VIEW.name(), hoverType.toUpperCase())) {
                Table view = metaData.view(connection, new ViewMetadataRequest(databaseName, schemaName, hoverText));
                if (Objects.nonNull(view)) {
                    sqlHover.setDatabaseName(databaseName);
                    sqlHover.setSchemaName(schemaName);
                    sqlHover.setViewName(view.getName());
                    sqlHover.setComment(view.getComment());
                    sqlHover.setDdl(view.getDdl());
                    sqlHovers.add(sqlHover);
                    return sqlHovers;
                }
            } else if (StringUtils.equalsAny(hoverType.toUpperCase(), IdentifierTypeEnum.UDF_FUNCTION.name(),
                    IdentifierTypeEnum.FUNCTION.name()
            )) {
                Function function = metaData.function(connection,
                        new FunctionMetadataRequest(databaseName, schemaName, hoverText));
                if (Objects.nonNull(function)) {
                    sqlHover.setDatabaseName(databaseName);
                    sqlHover.setSchemaName(schemaName);
                    sqlHover.setComment(function.getRemarks());
                    sqlHover.setDdl(function.getFunctionBody());
                    sqlHovers.add(sqlHover);
                    return sqlHovers;
                }
            } else if (Objects.equals(IdentifierTypeEnum.PROCEDURE.name(), hoverType.toUpperCase())) {
                Procedure procedure = metaData.procedure(connection,
                        new ProcedureMetadataRequest(databaseName, schemaName, hoverText));
                if (Objects.nonNull(procedure)) {
                    sqlHover.setDatabaseName(databaseName);
                    sqlHover.setSchemaName(schemaName);
                    sqlHover.setComment(procedure.getRemarks());
                    sqlHover.setDdl(procedure.getProcedureBody());
                    sqlHovers.add(sqlHover);
                    return sqlHovers;
                }
            } else if (Objects.equals(IdentifierTypeEnum.TRIGGER.name(), hoverType.toUpperCase())) {
                Trigger trigger = metaData.trigger(connection,
                        new TriggerMetadataRequest(databaseName, schemaName, hoverText));
                if (Objects.nonNull(trigger)) {
                    sqlHover.setDatabaseName(databaseName);
                    sqlHover.setSchemaName(schemaName);
                    sqlHover.setDdl(trigger.getTriggerBody());
                    sqlHovers.add(sqlHover);
                    return sqlHovers;
                }
            } else if (Objects.equals(IdentifierTypeEnum.COLUMN.name(), hoverType.toUpperCase())) {
                if (StringUtils.isNotBlank(tableName)) {
                    SqlStatement currentStatement = sqlHoverParam.getCurrentStatement();
                    if (Objects.isNull(currentStatement)
                            || CollectionUtils.isEmpty(currentStatement.getIdentifiers())) {
                        sqlHovers.add(sqlHover);
                        return sqlHovers;
                    }
                    List<SimpleIdentifier> identifiers = currentStatement.getIdentifiers();
                    List<SimpleIdentifier> tableIdentifiers = new ArrayList<>();
                    for (SimpleIdentifier identifier : identifiers) {
                        if (IdentifierTypeEnum.TABLE.name().equals(identifier.getType()) && tableName.equals(identifier.getAlias())) {
                            tableIdentifiers.add(identifier);
                        }
                    }
                    if (CollectionUtils.isEmpty(tableIdentifiers)) {
                        tableIdentifiers = identifiers.stream()
                                .filter(identifier -> IdentifierTypeEnum.TABLE.name().equals(identifier.getType())).toList();
                    }
                    for (SimpleIdentifier tableIdentifier : tableIdentifiers) {
                        String name = tableIdentifier.getName();
                        if (StringUtils.isBlank(name)) {
                            continue;
                        }
                        String identifierDatabase = tableIdentifier.getIdentifierDatabase();
                        if (StringUtils.isBlank(identifierDatabase)) {
                            identifierDatabase = sqlHoverParam.getDatabaseName();
                        }
                        String identifierSchema = tableIdentifier.getIdentifierSchema();
                        if (StringUtils.isBlank(identifierSchema)) {
                            identifierSchema = sqlHoverParam.getSchemaName();
                        }
                        setColumnHovers(datasourceId, identifierDatabase, identifierSchema, name,
                                metaData, connection, hoverText, datasourceName, sqlHovers);
                    }
                    sqlHover = null;
                    return sqlHovers;
                } else {
                    SqlStatement currentStatement = sqlHoverParam.getCurrentStatement();
                    if (Objects.isNull(currentStatement)
                            || CollectionUtils.isEmpty(currentStatement.getIdentifiers())) {
                        sqlHovers.add(sqlHover);
                        return sqlHovers;
                    }
                    List<SimpleIdentifier> identifiers = currentStatement.getIdentifiers();
                    List<SimpleIdentifier> tableIdentifiers = identifiers.stream()
                            .filter(identifier -> IdentifierTypeEnum.TABLE.name().equals(identifier.getType()))
                            .toList();
                    for (SimpleIdentifier tableIdentifier : tableIdentifiers) {
                        String identifierDatabase = tableIdentifier.getIdentifierDatabase();
                        String identifierSchema = tableIdentifier.getIdentifierSchema();
                        if (StringUtils.isBlank(identifierDatabase)) {
                            identifierDatabase = sqlHoverParam.getDatabaseName();
                        }
                        if (StringUtils.isBlank(identifierSchema)) {
                            identifierSchema = sqlHoverParam.getSchemaName();
                        }
                        String name = tableIdentifier.getName();
                        if (StringUtils.isBlank(name)) {
                            continue;
                        }
                        setColumnHovers(datasourceId, identifierDatabase, identifierSchema, name,
                                metaData, connection, hoverText, datasourceName, sqlHovers);
                    }
                    sqlHover = null;
                    return sqlHovers;
                }
            }
            return sqlHovers;
        } catch (Exception e) { // impl-contract: fallback - hover failure disables hover hints only.
            log.warn("sql hover error", e);
            return List.of();
        }
    }

    @Override
    public SqlContextParser quickParser(DbSqlContextParserRequest sqlContextParserParam) {
        String sql = sqlContextParserParam.getSql();
        if (StringUtils.isBlank(sql) || sql.length() > 50000) {
            return null;
        }
        try {
            SqlContextParser sqlContextParser = new SqlContextParser();
            String dbType = Chat2DBContext.getDBConfig().getDbType();
            if (StringUtils.equalsAny(dbType.toUpperCase(),
                    DatabaseTypeEnum.REDIS.name(),
                    DatabaseTypeEnum.MONGODB.name())) {
                return null;
            }
            ISQLParser sqlParser = DefaultSqlSyntaxHandler.getSQLParser(dbType.toUpperCase());
            if (Objects.isNull(sqlParser)) {
                return null;
            }
            SqlParserResponse sqlParserResult = sqlParser.simpleParserStatements(sql);
            List<Statement> statements = sqlParserResult.getStatements();

            if (CollectionUtils.isEmpty(statements)) {
                return null;
            }
            List<SqlStatement> sqlStatements = new ArrayList<>();

            for (Statement statement : statements) {
                String sqlType = statement.getType();
                if (!SUPPORT_SYNTAX_CHECK_DB.contains(dbType.toUpperCase())
                        && !SqlTypeEnum.SELECT.name().equals(sqlType)) {
                    continue;
                }
                SqlStatement sqlStatement = new SqlStatement();
                String statementType = statement.getStatementType();
                String statementSql = statement.getSql();
                sqlStatement.setSql(statementSql);
                sqlStatement.setType(sqlType);
                Token statementFirstToken = statement.getFirstToken();
                Token statementLastToken = statement.getLastToken();
                if (Objects.nonNull(statementFirstToken)) {
                    sqlStatement.setSqlStartRowNum(statementFirstToken.getLine());
                    sqlStatement.setSqlStartColNum(statementFirstToken.getCharPositionInLine() + 1);
                }
                if (Objects.nonNull(statementLastToken)) {
                    sqlStatement.setSqlEndRowNum(statementLastToken.getLine());
                    sqlStatement.setSqlEndColNum(statementLastToken.getCharPositionInLine()
                            + statementLastToken.getText().length() + 1);
                }
                sqlStatement.setComment(statement.getComment());
                sqlStatement.setStatementType(statementType);
                sqlStatement.setInsertValueMappings(getSimpleInsertValueMappings(statement.getInsertValueMappings()));

                sqlStatement.setIdentifiers(List.of());

                sqlStatements.add(sqlStatement);
            }
            sqlContextParser.setSqlStatementList(sqlStatements);

            sqlContextParser.setMarkMessageList(List.of());
            return sqlContextParser;
        } catch (Exception e) { // impl-contract: fallback - quick parser failure disables quick hints only.
            log.error("sql parser error ", e);
            SqlContextParser fallback = new SqlContextParser();
            fallback.setSqlStatementList(List.of());
            fallback.setMarkMessageList(List.of());
            return fallback;
        }
    }

    private void fetchAndSetSchemas(SqlKeyword sqlKeyword, Connection connection,
                                    String dbType, ISQLIdentifierProcessor sqlIdentifierProcessor, String databaseName, IDbMetaData metaData, String databaseSourceName) {
        List<Schema> schemas = metaData.schemas(connection, databaseName);
        if (CollectionUtils.isNotEmpty(schemas)) {
            setSchemas(sqlKeyword, schemas, dbType, sqlIdentifierProcessor, databaseName, databaseSourceName);
        }
    }

    private void setSchemas(SqlKeyword sqlKeyword, List<Schema> schemas,
                            String dbType, ISQLIdentifierProcessor identifierProcessor,
                            String databaseName, String databaseSourceName) {
        List<SimpleSchema> simpleSchemas = schemas.stream().map(schema -> {
            SimpleSchema simpleSchema = new SimpleSchema();
            simpleSchema.setDatabaseName(databaseName);
            simpleSchema.setDatasourceName(databaseSourceName);
            String name = schema.getName();
            simpleSchema.setSchemaName(name);
            simpleSchema.setInsertText(identifierProcessor.quoteIdentifier(name));
            return simpleSchema;
        }).toList();
        if (CollectionUtils.isNotEmpty(simpleSchemas)) {
            sqlKeyword.setSchemas(simpleSchemas);
        } else {
            sqlKeyword.setSchemas(List.of());
        }
    }

    private void fetchAndSetDatabases(SqlKeyword sqlKeyword, Connection connection,
                                      IDbMetaData metaData, String databaseSourceName) {
        List<Database> databases = metaData.databases(connection);
        if (CollectionUtils.isNotEmpty(databases)) {
            setDatabases(sqlKeyword, databases, databaseSourceName);
        }
    }

    private List<SimpleInsertValueMapping> getSimpleInsertValueMappings(List<InsertValueMapping> insertValueMappings) {
        if (CollectionUtils.isEmpty(insertValueMappings)) {
            return List.of();
        }
        return insertValueMappings.stream()
                .map(insertValueMapping -> {
                    SimpleInsertValueMapping simpleInsertValueMapping = new SimpleInsertValueMapping();
                    if (Objects.nonNull(insertValueMapping.getColumnFirstToken())
                            && Objects.nonNull(insertValueMapping.getColumnLastToken())) {
                        setColumnTokenRange(simpleInsertValueMapping, insertValueMapping.getColumnFirstToken(),
                                insertValueMapping.getColumnLastToken());
                    }
                    if (Objects.nonNull(insertValueMapping.getValueFirstToken())
                            && Objects.nonNull(insertValueMapping.getValueLastToken())) {
                        setValueTokenRange(simpleInsertValueMapping, insertValueMapping.getValueFirstToken(),
                                insertValueMapping.getValueLastToken());
                    }
                    if (Objects.nonNull(insertValueMapping.getRowFirstToken())
                            && Objects.nonNull(insertValueMapping.getRowLastToken())) {
                        setRowTokenRange(simpleInsertValueMapping, insertValueMapping.getRowFirstToken(),
                                insertValueMapping.getRowLastToken());
                    }
                    simpleInsertValueMapping.setRowIndex(insertValueMapping.getRowIndex());
                    simpleInsertValueMapping.setColumnIndex(insertValueMapping.getColumnIndex());
                    InsertValueMappingStatusEnum mappingStatus = Objects.isNull(insertValueMapping.getMappingStatus())
                            ? InsertValueMappingStatusEnum.MATCHED : insertValueMapping.getMappingStatus();
                    simpleInsertValueMapping.setMappingStatus(mappingStatus.name());
                    return simpleInsertValueMapping;
                }).toList();
    }

    private List<SimpleColumn> getSimpleColumns(List<TableColumn> tableColumns, String datasourceName,
                                                String databaseName, String schemaName, String tableName) {
        List<SimpleColumn> simpleColumns = new ArrayList<>(tableColumns.size());

        for (TableColumn tableColumn : tableColumns) {
            SimpleColumn simpleColumn = new SimpleColumn(tableColumn);
            simpleColumn.setDatasourceName(datasourceName);
            simpleColumns.add(simpleColumn);
        }
        return simpleColumns;
    }

    private void setColumnHovers(Long datasourceId, String identifierDatabase, String identifierSchema,
                                 String name, IDbMetaData metaData, Connection connection, String hoverText,
                                 String datasourceName, ArrayList<SqlHover> sqlHovers) {
        List<TableColumn> columns = getTableColumns(datasourceId, identifierDatabase, identifierSchema,
                name, metaData, connection, hoverText);
        for (TableColumn tableColumn : columns) {
            String columnName = tableColumn.getName();
            if (hoverText.equals(columnName)) {
                SqlHover sqlHover1 = new SqlHover();
                sqlHover1.setDatabaseName(identifierDatabase);
                sqlHover1.setSchemaName(identifierSchema);
                sqlHover1.setTableName(name);
                sqlHover1.setDatasourceName(datasourceName);
                sqlHover1.setDataType(tableColumn.getColumnType());
                sqlHover1.setColumnName(columnName);
                sqlHover1.setComment(tableColumn.getComment());
                sqlHovers.add(sqlHover1);
            }
        }
    }

    private void setDatabases(SqlKeyword sqlKeyword, List<Database> databases,
                              String databaseSourceName) {
        List<SimpleDatabase> simpleDatabases = databases.stream().map(database -> {
            SimpleDatabase simpleDatabase = new SimpleDatabase();
            simpleDatabase.setDatabaseName(database.getName());
            simpleDatabase.setDatasourceName(databaseSourceName);
            return simpleDatabase;
        }).toList();
        if (CollectionUtils.isNotEmpty(simpleDatabases)) {
            sqlKeyword.setDatabases(simpleDatabases);
        } else {
            sqlKeyword.setDatabases(List.of());
        }
    }

    private void setColumnTokenRange(SimpleInsertValueMapping simpleInsertValueMapping, Token firstToken,
                                     Token lastToken) {
        simpleInsertValueMapping.setColumnStartRowNum(firstToken.getLine());
        simpleInsertValueMapping.setColumnStartColNum(firstToken.getCharPositionInLine() + 1);
        if (lastToken instanceof IntervalToken intervalToken) {
            simpleInsertValueMapping.setColumnEndRowNum(intervalToken.getEndLine());
            simpleInsertValueMapping.setColumnEndColNum(intervalToken.getEndColumn() + 1);
        } else {
            simpleInsertValueMapping.setColumnEndRowNum(lastToken.getLine());
            simpleInsertValueMapping.setColumnEndColNum(lastToken.getCharPositionInLine()
                    + lastToken.getText().length() + 1);
        }
    }

    private void setValueTokenRange(SimpleInsertValueMapping simpleInsertValueMapping, Token firstToken,
                                    Token lastToken) {
        simpleInsertValueMapping.setValueStartRowNum(firstToken.getLine());
        simpleInsertValueMapping.setValueStartColNum(firstToken.getCharPositionInLine() + 1);
        if (lastToken instanceof IntervalToken intervalToken) {
            simpleInsertValueMapping.setValueEndRowNum(intervalToken.getEndLine());
            simpleInsertValueMapping.setValueEndColNum(intervalToken.getEndColumn() + 1);
        } else {
            simpleInsertValueMapping.setValueEndRowNum(lastToken.getLine());
            simpleInsertValueMapping.setValueEndColNum(lastToken.getCharPositionInLine()
                    + lastToken.getText().length() + 1);
        }
    }

    private void setRowTokenRange(SimpleInsertValueMapping simpleInsertValueMapping, Token firstToken,
                                  Token lastToken) {
        simpleInsertValueMapping.setRowStartRowNum(firstToken.getLine());
        simpleInsertValueMapping.setRowStartColNum(firstToken.getCharPositionInLine() + 1);
        if (lastToken instanceof IntervalToken intervalToken) {
            simpleInsertValueMapping.setRowEndRowNum(intervalToken.getEndLine());
            simpleInsertValueMapping.setRowEndColNum(intervalToken.getEndColumn() + 1);
        } else {
            simpleInsertValueMapping.setRowEndRowNum(lastToken.getLine());
            simpleInsertValueMapping.setRowEndColNum(lastToken.getCharPositionInLine()
                    + lastToken.getText().length() + 1);
        }
    }

    private List<TableColumn> getTableColumns(Long datasourceId, String database,
                                              String schema, String tableName, IDbMetaData metaData,
                                              Connection connection, String columnName) {
        String columnKey = getColumnKey(datasourceId, database, schema, tableName);
        DbTableQueryRequest queryParam = DbTableQueryRequest.builder().dataSourceId(datasourceId)
                .databaseName(database).schemaName(schema)
                .tableName(tableName).build();
        List<TableColumn> columns = tableService.queryColumns(queryParam);
        if (CollectionUtils.isEmpty(columns)) {
            columns = metaData.columns(connection, new ColumnMetadataRequest(database, schema, tableName, columnName));
        }
        return columns;
    }

}
