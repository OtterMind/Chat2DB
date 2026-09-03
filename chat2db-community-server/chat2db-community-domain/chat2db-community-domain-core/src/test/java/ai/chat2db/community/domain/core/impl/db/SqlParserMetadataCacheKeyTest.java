package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.parser.IdentifierTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.StatementValidTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.parser.position.TokenPosition;
import ai.chat2db.community.domain.api.model.parser.result.SqlParserResponse;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.create.CreateTableStatement;
import ai.chat2db.community.domain.api.model.parser.token.Identifier;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlContextParserRequest;
import ai.chat2db.community.domain.core.cache.CacheKey;
import ai.chat2db.community.domain.core.cache.MemoryCacheManage;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.domain.api.service.task.ITaskProgressListener;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.DefaultSqlSyntaxHandler;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlParserMetadataCacheKeyTest {

    private static final String DB_TYPE = "SQL_PARSER_CACHE_KEY_TEST";
    private static final long DATA_SOURCE_ID = 2482L;
    private static final long CONSOLE_ID = 8242L;
    private static final String CONSOLE_DATABASE = "console_db";
    private static final String CONSOLE_SCHEMA = "console_schema";
    private static final String IDENTIFIER_DATABASE = "identifier_db";
    private static final String IDENTIFIER_SCHEMA = "identifier_schema";
    private static final String TABLE_NAME = "orders";

    private IPlugin previousPlugin;
    private ISqlSyntaxPlugin previousSyntaxPlugin;

    @BeforeEach
    void setUp() throws Exception {
        AtomicReference<TableMetadataRequest> capturedRequest = new AtomicReference<>();
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setSupportDatabase(true);
        config.setSupportSchema(true);
        config.setDefaultDriverConfig(new DriverConfig());
        IDbMetaData metadata = new DefaultMetaService() {
            @Override
            public List<Table> tables(Connection connection, TablesRequest request) {
                return List.of(table(TABLE_NAME));
            }

            @Override
            public List<TableColumn> columns(Connection connection, TableMetadataRequest request) {
                capturedRequest.set(request);
                return List.of(TableColumn.builder()
                        .name("id")
                        .databaseName(request.getDatabaseName())
                        .schemaName(request.getSchemaName())
                        .tableName(request.getTableName())
                        .build());
            }
        };
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new TestPlugin(config, metadata));
        previousSyntaxPlugin = syntaxPlugins().put(DB_TYPE, new TestSyntaxPlugin());
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDataSourceId(DATA_SOURCE_ID);
        connectInfo.setAlias("test-datasource");
        connectInfo.setDriverConfig(config.getDefaultDriverConfig());
        connectInfo.setConnection(connection());
        Chat2DBContext.putContext(connectInfo);
        CapturedRequestHolder.REQUEST = capturedRequest;
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        CapturedRequestHolder.REQUEST = null;
        MemoryCacheManage.remove(CacheKey.getTableKey(DATA_SOURCE_ID, CONSOLE_DATABASE, CONSOLE_SCHEMA));
        MemoryCacheManage.remove(CacheKey.getColumnKey(DATA_SOURCE_ID, IDENTIFIER_DATABASE, IDENTIFIER_SCHEMA, TABLE_NAME));
        MemoryCacheManage.remove(CacheKey.getConsoleParserKey(DATA_SOURCE_ID, CONSOLE_ID));
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        if (previousSyntaxPlugin == null) {
            syntaxPlugins().remove(DB_TYPE);
        } else {
            syntaxPlugins().put(DB_TYPE, previousSyntaxPlugin);
        }
    }

    @Test
    void tableColumnLoaderUsesIdentifierDatabaseAndSchemaForCrossDatabaseReference() {
        DbSqlContextParserRequest request = new DbSqlContextParserRequest();
        request.setDataSourceId(DATA_SOURCE_ID);
        request.setConsoleId(CONSOLE_ID);
        request.setDatabaseName(CONSOLE_DATABASE);
        request.setSchemaName(CONSOLE_SCHEMA);
        request.setSql("select * from identifier_db.identifier_schema.orders");

        new DbSqlParserServiceImpl().contextParser(request);

        TableMetadataRequest captured = CapturedRequestHolder.REQUEST.get();
        assertEquals(new TableMetadataRequest(IDENTIFIER_DATABASE, IDENTIFIER_SCHEMA, TABLE_NAME), captured);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ISqlSyntaxPlugin> syntaxPlugins() throws Exception {
        Field field = DefaultSqlSyntaxHandler.class.getDeclaredField("sqlSyntaxPluginMap");
        field.setAccessible(true);
        return (Map<String, ISqlSyntaxPlugin>) field.get(null);
    }

    private static Table table(String name) {
        return Table.builder().name(name).databaseName(CONSOLE_DATABASE).schemaName(CONSOLE_SCHEMA).build();
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "close" -> null;
                    case "toString" -> "SqlParserMetadataCacheKeyTestConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private record TestPlugin(DBConfig config, IDbMetaData metadata) implements IPlugin {
        @Override
        public DBConfig getDBConfig() {
            return config;
        }

        @Override
        public IDbMetaData getDbMetaData() {
            return metadata;
        }
    }

    private static final class TestSyntaxPlugin implements ISqlSyntaxPlugin {
        @Override
        public String getDatabaseType() {
            return DB_TYPE;
        }

        @Override
        public ISQLParser getSQLParser() {
            return new TestParser();
        }
    }

    private static final class TestParser implements ISQLParser {
        @Override
        public SqlParserResponse parserStatements(String sql) {
            Statement statement = new Statement(sql);
            statement.setType(SqlTypeEnum.SELECT.name());
            statement.setStatementType(StatementValidTypeEnum.VALID.name());
            Identifier identifier = new Identifier();
            identifier.setIdentifierType(IdentifierTypeEnum.TABLE.name());
            identifier.setIdentifierDatabase(IDENTIFIER_DATABASE);
            identifier.setIdentifierSchema(IDENTIFIER_SCHEMA);
            identifier.setIdentifierName(TABLE_NAME);
            statement.setIdentifiers(new ArrayList<>(List.of(identifier)));
            return SqlParserResponse.builder().statements(List.of(statement)).syntaxErrors(List.of()).build();
        }

        @Override
        public SqlParserResponse simpleParserStatements(String sql) {
            return parserStatements(sql);
        }

        @Override
        public List<Token> getAllTokens(String sql) {
            return List.of();
        }

        @Override
        public List<Token> getAllTokensOnDefault(String sql) {
            return List.of();
        }

        @Override
        public Map<TokenPosition, Token> getTokenPositionMap(String sql) {
            return Map.of();
        }

        @Override
        public CreateTableStatement parserCreateTableStatement(String sql) {
            return null;
        }

        @Override
        public java.util.Set<String> getSqlStartKeywords() {
            return java.util.Set.of("SELECT");
        }

        @Override
        public boolean isSelect(String sql) {
            return true;
        }

        @Override
        public List<Statement> parserSqlScript(String sql) {
            return List.of();
        }

        @Override
        public int parserSqlScript(File file, ITaskProgressListener progressListener, ISqlBatchHandler sqlBatchHandler) {
            return 0;
        }

        @Override
        public SqlParserResponse validTableStatements(String sql) {
            return parserStatements(sql);
        }
    }

    private static final class CapturedRequestHolder {
        private static AtomicReference<TableMetadataRequest> REQUEST;
    }
}
