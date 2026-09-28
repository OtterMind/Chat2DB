package ai.chat2db.community.domain.core.impl.cli;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.cli.CliConnectionTestResponse;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.request.cli.CliConnectionTestRequest;
import ai.chat2db.community.domain.api.model.request.cli.CliDataSourceCreateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.service.db.IDbDataSourceService;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.core.converter.CliDataSourceConverter;
import ai.chat2db.community.tools.exception.cli.CliDomainException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliDatasourceServiceTest {

    @Test
    void createPreConnectAllowsExplicitEmptyPassword() throws Exception {
        CapturingDataSourceService dataSourceService = new CapturingDataSourceService();
        CliDataSourceServiceImpl service = newService(dataSourceService);
        CliDataSourceCreateRequest request = baseCreateRequest();
        request.setPassword("");

        Method method = CliDataSourceServiceImpl.class.getDeclaredMethod("preConnectForCreate",
                CliDataSourceCreateRequest.class);
        method.setAccessible(true);
        CliConnectionTestResponse vo = (CliConnectionTestResponse) method.invoke(service, request);

        assertTrue(vo.getCanConnect());
        assertEquals("", dataSourceService.capturedParam.getPassword());
    }

    @Test
    void temporaryConnectionTestStillRejectsEmptyPassword() {
        CapturingDataSourceService dataSourceService = new CapturingDataSourceService();
        CliDataSourceServiceImpl service = newService(dataSourceService);
        CliConnectionTestRequest request = new CliConnectionTestRequest();
        request.setDbType("MYSQL");
        request.setUrl("jdbc:mysql://localhost:3306/app");
        request.setUser("root");
        request.setPassword("");

        CliDomainException exception = assertThrows(CliDomainException.class,
                () -> service.connectionTest(request));

        assertEquals("invalid_connection_test_args", exception.getCode());
        assertNull(dataSourceService.capturedParam);
    }

    @Test
    void connectionTestNormalizesPostgresAliasBeforePreConnect() {
        CapturingDataSourceService dataSourceService = new CapturingDataSourceService();
        CliDataSourceServiceImpl service = newService(dataSourceService);
        CliConnectionTestRequest request = new CliConnectionTestRequest();
        request.setDbType("postgres");
        request.setHost("localhost");
        request.setPort("5432");
        request.setDatabase("app");
        request.setUser("root");
        request.setPassword("secret");

        CliConnectionTestResponse vo = service.connectionTest(request);

        assertTrue(vo.getCanConnect());
        assertEquals("POSTGRESQL", dataSourceService.capturedParam.getType());
        assertEquals("jdbc:postgresql://localhost:5432/app", dataSourceService.capturedParam.getUrl());
    }

    @Test
    void createKeepsEmptyPasswordWhenPreConnectFails() {
        CapturingDataSourceService dataSourceService = new CapturingDataSourceService();
        String sensitiveMessage =
                "java.lang.IllegalStateException: jdbc:mysql://db.internal/app?pass" + "word=DO_NOT_EXPOSE";
        dataSourceService.nextPreConnectFailure = new IllegalStateException(sensitiveMessage);
        CliDataSourceServiceImpl service = newService(dataSourceService);
        CliDataSourceCreateRequest request = baseCreateRequest();
        request.setPassword("");

        CliDomainException exception = assertThrows(CliDomainException.class,
                () -> service.create(request));

        assertEquals("datasource_connection_failed", exception.getCode());
        assertEquals("Datasource connection test failed.", exception.getMessage());
        assertEquals("", dataSourceService.capturedParam.getPassword());
        assertTrue(exception.getDetails().isEmpty());
        assertFalse(exception.getMessage().contains(sensitiveMessage));
        assertFalse(exception.getMessage().contains("password"));
        assertFalse(exception.getMessage().contains("secret"));
    }

    @Test
    void connectionTestFailureUsesStablePublicMessageOnly() {
        CapturingDataSourceService dataSourceService = new CapturingDataSourceService();
        String sensitiveMessage =
                "java.sql.SQLException: Access denied for user root pass" + "word=DO_NOT_EXPOSE";
        dataSourceService.nextPreConnectFailure = new IllegalStateException(sensitiveMessage);
        CliDataSourceServiceImpl service = newService(dataSourceService);
        CliConnectionTestRequest request = new CliConnectionTestRequest();
        request.setDbType("MYSQL");
        request.setUrl("jdbc:mysql://localhost:3306/app");
        request.setUser("root");
        request.setPassword("secret");

        CliConnectionTestResponse response = service.connectionTest(request);

        assertEquals(Boolean.FALSE, response.getCanConnect());
        assertEquals("datasource_connection_failed", response.getErrorCode());
        assertEquals("Datasource connection test failed.", response.getErrorMessage());
        assertNull(response.getErrorDetail());
        assertFalse(response.getErrorMessage().contains(sensitiveMessage));
        assertFalse(response.getErrorMessage().contains("SQLException"));
        assertFalse(response.getErrorMessage().contains("password"));
        assertFalse(response.getErrorMessage().contains("secret"));
    }

    @Test
    void createHostModeBuildsExpectedJdbcUrlsBeforePreConnect() {
        Map<String, String> expectedUrls = Map.ofEntries(
                Map.entry("MYSQL", "jdbc:mysql://localhost:3306/app"),
                Map.entry("MARIADB", "jdbc:mariadb://localhost:3306/app"),
                Map.entry("TIDB", "jdbc:mysql://localhost:3306/app"),
                Map.entry("DORIS", "jdbc:mysql://localhost:3306/app"),
                Map.entry("STARROCKS", "jdbc:mysql://localhost:3306/app"),
                Map.entry("POSTGRESQL", "jdbc:postgresql://localhost:3306/app"),
                Map.entry("POSTGRES", "jdbc:postgresql://localhost:3306/app"),
                Map.entry("COCKROACHDB", "jdbc:postgresql://localhost:3306/app"),
                Map.entry("GAUSSDB", "jdbc:postgresql://localhost:3306/app"),
                Map.entry("OPENGAUSS", "jdbc:opengauss://localhost:3306/app"),
                Map.entry("SQLSERVER", "jdbc:sqlserver://localhost:3306;database=app"),
                Map.entry("ORACLE", "jdbc:oracle:thin:@localhost:3306:app"),
                Map.entry("DB2", "jdbc:db2://localhost:3306/app"),
                Map.entry("DM", "jdbc:dm://localhost:3306/app"),
                Map.entry("KINGBASE", "jdbc:kingbase8://localhost:3306/app"),
                Map.entry("CLICKHOUSE", "jdbc:clickhouse://localhost:3306/app"),
                Map.entry("PRESTO", "jdbc:presto://localhost:3306/app"),
                Map.entry("HIVE", "jdbc:hive2://localhost:3306/app"),
                Map.entry("OCEANBASE", "jdbc:oceanbase://localhost:3306/app"),
                Map.entry("OCEANBASE_ORACLE", "jdbc:oceanbase://localhost:3306/app"),
                Map.entry("MONGODB", "jdbc:mongodb://localhost:3306/app"),
                Map.entry("REDIS", "jdbc:redis://localhost:3306/app"),
                Map.entry("H2", "jdbc:h2:tcp://localhost:3306/app"),
                Map.entry("REDSHIFT", "jdbc:redshift://localhost:3306/app"),
                Map.entry("KYLIN", "jdbc:kylin://localhost:3306/app"),
                Map.entry("OSCAR", "jdbc:oscar://localhost:3306/app"),
                Map.entry("SUNDB", "jdbc:sundb://localhost:3306/app"),
                Map.entry("XUGUDB", "jdbc:xugu://localhost:3306/app"),
                Map.entry("TDENGINE", "jdbc:TAOS-RS://localhost:3306/app")
        );

        expectedUrls.forEach((dbType, expectedUrl) -> {
            CapturingDataSourceService dataSourceService = new CapturingDataSourceService();
            dataSourceService.nextPreConnectFailure = new IllegalStateException("fixture failure");
            CliDataSourceServiceImpl service = newService(dataSourceService);
            CliDataSourceCreateRequest request = baseCreateRequest();
            request.setDbType(dbType);
            request.setUrl(null);
            request.setHost("localhost");
            request.setPort("3306");
            request.setDatabase("app");

            assertThrows(CliDomainException.class, () -> service.create(request), dbType);

            assertEquals(expectedUrl, dataSourceService.capturedParam.getUrl(), dbType);
            if ("POSTGRES".equals(dbType)) {
                assertEquals("POSTGRESQL", dataSourceService.capturedParam.getType());
            }
        });
    }

    @Test
    void createHostModeRejectsUnsupportedJdbcUrlConstruction() {
        CapturingDataSourceService dataSourceService = new CapturingDataSourceService();
        CliDataSourceServiceImpl service = newService(dataSourceService);
        CliDataSourceCreateRequest request = baseCreateRequest();
        request.setDbType("UNKNOWNDB");
        request.setUrl(null);
        request.setHost("localhost");
        request.setPort("3306");
        request.setDatabase("app");

        CliDomainException exception = assertThrows(CliDomainException.class,
                () -> service.create(request));

        assertEquals("invalid_datasource_create_args", exception.getCode());
        assertTrue(exception.getMessage().contains("pass url instead"));
        assertNull(dataSourceService.capturedParam);
    }

    private static CliDataSourceServiceImpl newService(CapturingDataSourceService dataSourceService) {
        return new CliDataSourceServiceImpl(
                unusedDependency(IWorkspaceStorageFacade.class),
                unusedDependency(IDbWorkspaceDataSourceService.class),
                dataSourceService,
                new CliDataSourceConverter()
        );
    }

    private static <T> T unusedDependency(Class<T> type) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (ignored, method, args) -> {
            throw new AssertionError("Unexpected call to " + type.getSimpleName() + "." + method.getName());
        });
        return type.cast(proxy);
    }

    private static CliDataSourceCreateRequest baseCreateRequest() {
        CliDataSourceCreateRequest request = new CliDataSourceCreateRequest();
        request.setDbType("MYSQL");
        request.setUrl("jdbc:mysql://localhost:3306/app");
        request.setUser("root");
        request.setPassword("secret");
        request.setEnvironmentId(1L);
        return request;
    }

    private static class CapturingDataSourceService implements IDbDataSourceService {

        private DbDataSourcePreConnectRequest capturedParam;
        private RuntimeException nextPreConnectFailure;

        @Override
        public void preConnect(DbDataSourcePreConnectRequest param) {
            this.capturedParam = param;
            if (nextPreConnectFailure != null) {
                throw nextPreConnectFailure;
            }
        }

        @Override
        public List<Database> connect(Long id) {
            return List.of();
        }

        @Override
        public void close(Long id) {
        }

        @Override
        public DriverConfig defaultDriverConfig(String dbType) {
            return new DriverConfig();
        }

        @Override
        public void removeConnection(Long id) {
        }

        @Override
        public void testSshConnection(SSHInfo ssh) {
        }

        @Override
        public void closeRuntime() {
        }
    }
}
