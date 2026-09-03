package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.db.DbSessionKillResult;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbObjectsQueryRequest;
import ai.chat2db.community.domain.api.model.request.runtime.McpConnectionContextRequest;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbSessionService;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoHandler;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbSessionControllerContractTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void listAndKillRequestsRequireDatasourceContext() {
        DbSessionController.SessionListRequest listRequest = new DbSessionController.SessionListRequest();
        assertFalse(validator.validate(listRequest).isEmpty());
        listRequest.setDataSourceId(42L);
        assertTrue(validator.validate(listRequest).isEmpty());

        DbSessionController.KillRequest killRequest = new DbSessionController.KillRequest();
        killRequest.setConnectionId(12L);
        killRequest.setKillType("QUERY");
        assertFalse(validator.validate(killRequest).isEmpty());
        killRequest.setDataSourceId(42L);
        assertTrue(validator.validate(killRequest).isEmpty());
    }

    @Test
    void killRequestCarriesDatasourceContextIntoConnectionAspect() throws Throwable {
        RecordingConnectionContextService contextService = new RecordingConnectionContextService();
        ConnectionInfoHandler handler = new ConnectionInfoHandler();
        Field field = ConnectionInfoHandler.class.getDeclaredField("connectionContextService");
        field.setAccessible(true);
        field.set(handler, contextService);

        DbSessionController.KillRequest request = new DbSessionController.KillRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setConnectionId(12L);
        request.setKillType("CONNECTION");

        Object result = handler.connectionInfoHandler(joinPoint(request, "ok"));

        assertEquals("ok", result);
        assertEquals(42L, contextService.bound.get().getDataSourceId());
        assertEquals("app", contextService.bound.get().getDatabaseName());
        assertEquals("public", contextService.bound.get().getSchemaName());
        assertEquals(1, contextService.clearCount);
    }

    @Test
    void controllerReturnsTypedKillOutcomeFromService() throws Exception {
        AtomicReference<Long> connectionId = new AtomicReference<>();
        AtomicReference<String> killType = new AtomicReference<>();
        IDbSessionService sessionService = (IDbSessionService) Proxy.newProxyInstance(
                IDbSessionService.class.getClassLoader(),
                new Class<?>[]{IDbSessionService.class},
                (proxy, method, args) -> {
                    if ("list".equals(method.getName())) {
                        return List.<Map<String, Object>>of();
                    }
                    if ("kill".equals(method.getName())) {
                        connectionId.set((Long) args[0]);
                        killType.set((String) args[1]);
                        return DbSessionKillResult.killed(12L, "QUERY", "KILL QUERY 12");
                    }
                    return defaultValue(method.getReturnType());
                });
        DbSessionController controller = new DbSessionController();
        Field field = DbSessionController.class.getDeclaredField("sessionService");
        field.setAccessible(true);
        field.set(controller, sessionService);
        DbSessionController.KillRequest request = new DbSessionController.KillRequest();
        request.setDataSourceId(42L);
        request.setConnectionId(12L);
        request.setKillType("QUERY");

        DbSessionKillResult result = controller.kill(request).getData();

        assertEquals(12L, connectionId.get());
        assertEquals("QUERY", killType.get());
        assertEquals("KILLED", result.getStatus());
        assertEquals("KILL QUERY 12", result.getSql());
    }

    private static ProceedingJoinPoint joinPoint(Object request, Object result) {
        return (ProceedingJoinPoint) Proxy.newProxyInstance(
                ProceedingJoinPoint.class.getClassLoader(),
                new Class<?>[]{ProceedingJoinPoint.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getArgs" -> new Object[]{request};
                    case "proceed" -> result;
                    case "toString" -> "DbSessionJoinPoint";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class RecordingConnectionContextService implements IDbConnectionContextService {
        private final AtomicReference<DbConnectionContextRequest> bound = new AtomicReference<>();
        private int clearCount;

        @Override
        public void bind(DbConnectionContextRequest dbConnectionContextRequest) {
            bound.set(dbConnectionContextRequest);
        }

        @Override
        public ConnectionProfile buildProfile(DbConnectionContextRequest dbConnectionContextRequest) {
            return null;
        }

        @Override
        public void bindProfile(ConnectionProfile profile) {
        }

        @Override
        public void bindMcp(McpConnectionContextRequest mcpConnectionContextRequest) {
        }

        @Override
        public void clear() {
            clearCount++;
        }

        @Override
        public void rebindCurrentDatabase(String databaseName) {
        }

        @Override
        public void close() {
        }

        @Override
        public ConnectionProfile currentProfile() {
            return null;
        }

        @Override
        public ConnectionProfile currentProfileSnapshot() {
            return null;
        }

        @Override
        public DriverConfig getDefaultDriverConfig(String dbType) {
            return null;
        }

        @Override
        public boolean supportCrossDatabase() {
            return false;
        }

        @Override
        public boolean supportCrossSchema() {
            return false;
        }

        @Override
        public boolean supportDatabase() {
            return false;
        }

        @Override
        public boolean supportSchema() {
            return false;
        }

        @Override
        public List<String> getSystemDatabases(String dbType) {
            return List.of();
        }

        @Override
        public List<String> getSystemSchemas(String dbType) {
            return List.of();
        }

        @Override
        public List<ForeignKeyInfo> getImportedKeys(String databaseName, String schemaName, String tableName) {
            return List.of();
        }

        @Override
        public List<Table> queryObjects(DbObjectsQueryRequest dbObjectsQueryRequest) {
            return List.of();
        }
    }
}
