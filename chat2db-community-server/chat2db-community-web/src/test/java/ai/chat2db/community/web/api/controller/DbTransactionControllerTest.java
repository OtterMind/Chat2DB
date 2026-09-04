package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbObjectsQueryRequest;
import ai.chat2db.community.domain.api.model.request.runtime.McpConnectionContextRequest;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.runtime.TransactionIsolationLevel;
import ai.chat2db.community.domain.api.model.runtime.TransactionStateResponse;
import ai.chat2db.community.domain.api.model.runtime.TransactionMode;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.config.exception.EasyControllerExceptionHandler;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.data.source.ConsoleCloseRequest;
import ai.chat2db.community.web.api.model.request.db.TransactionBeginRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.mapstruct.factory.Mappers;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.validation.constraints.NotNull;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbTransactionControllerTest {

    private Field messageSourceField;
    private MessageSource originalMessageSource;

    @BeforeEach
    void setUp() throws Exception {
        messageSourceField = I18nUtils.class.getDeclaredField("messageSourceStatic");
        messageSourceField.setAccessible(true);
        originalMessageSource = (MessageSource) messageSourceField.get(null);

        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("datasource.not.found", Locale.US, "Datasource not found.");
        messageSource.addMessage("transaction.datasource.mismatch", Locale.US, "Console datasource changed.");
        messageSourceField.set(null, messageSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        messageSourceField.set(null, originalMessageSource);
    }

    @Test
    void commitEndpointPassesOnlyDatasourceAndConsoleIdentityToService() throws Exception {
        AtomicReference<DbConnectionContextRequest> received = new AtomicReference<>();
        DbTransactionController controller = controller(new RecordingConnectionContextService(received, null));
        ConsoleCloseRequest request = new ConsoleCloseRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("shop");
        request.setSchemaName("public");
        request.setConsoleId(7001L);

        DataResult<TransactionStateResponse> result = controller.commit(request);

        assertTrue(result.success());
        assertFalse(result.getData().isInTransaction());

        assertEquals(42L, received.get().getDataSourceId());
        assertEquals(7001L, received.get().getConsoleId());
        assertNull(received.get().getDatabaseName());
        assertNull(received.get().getSchemaName());
    }

    @Test
    void releaseEndpointReturnsTransactionStateAndKeepsRequestDatabaseAndSchemaOutOfContext() throws Exception {
        AtomicReference<DbConnectionContextRequest> received = new AtomicReference<>();
        DbTransactionController controller = controller(new RecordingConnectionContextService(received, null));
        ConsoleCloseRequest request = new ConsoleCloseRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("shop");
        request.setSchemaName("public");
        request.setConsoleId(7002L);

        DataResult<TransactionStateResponse> result = controller.release(request);

        assertTrue(result.success());
        assertFalse(result.getData().isInTransaction());
        assertEquals(TransactionMode.AUTO, result.getData().getMode());
        assertEquals("ROLLED_BACK", result.getData().getOutcome());
        assertEquals(42L, received.get().getDataSourceId());
        assertEquals(7002L, received.get().getConsoleId());
        assertNull(received.get().getDatabaseName());
        assertNull(received.get().getSchemaName());
    }

    @Test
    void transactionEndpointPropagatesServiceFailure() throws Exception {
        DbTransactionController controller = controller(
                new RecordingConnectionContextService(new AtomicReference<>(), new RuntimeException("boom")));
        TransactionBeginRequest request = new TransactionBeginRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("tainted_db");
        request.setSchemaName("tainted_schema");
        request.setConsoleId(7003L);

        assertThrows(RuntimeException.class, () -> controller.begin(request));
    }

    @Test
    void beginEndpointPassesSelectedIsolationLevelToService() throws Exception {
        AtomicReference<DbConnectionContextRequest> received = new AtomicReference<>();
        DbTransactionController controller = controller(new RecordingConnectionContextService(received, null));
        TransactionBeginRequest request = new TransactionBeginRequest();
        request.setDataSourceId(42L);
        request.setConsoleId(7004L);
        request.setIsolationLevel(TransactionIsolationLevel.READ_COMMITTED);

        DataResult<TransactionStateResponse> result = controller.begin(request);

        assertTrue(result.success());
        assertEquals(42L, received.get().getDataSourceId());
        assertEquals(7004L, received.get().getConsoleId());
        assertEquals(TransactionIsolationLevel.READ_COMMITTED, received.get().getTransactionIsolationLevel());
    }

    @Test
    void exceptionHandlerReturnsBusinessErrorWhenDatasourceIsNotVisible() {
        EasyControllerExceptionHandler exceptionHandler = new EasyControllerExceptionHandler();

        ActionResult result = exceptionHandler.convert(
                new BusinessException("datasource.not.found"));

        assertFalse(result.success());
        assertEquals("datasource.not.found", result.errorCode());
    }

    @Test
    void transactionEndpointsUsePostMapping() throws Exception {
        assertOnlyPost("begin", TransactionBeginRequest.class);
        assertOnlyPost("commit", ConsoleCloseRequest.class);
        assertOnlyPost("rollback", ConsoleCloseRequest.class);
        assertOnlyPost("state", ConsoleCloseRequest.class);
        assertOnlyPost("release", ConsoleCloseRequest.class);
    }

    @Test
    void transactionRequestsRequireConsoleId() throws Exception {
        assertNotNull(ConsoleCloseRequest.class.getDeclaredField("consoleId").getAnnotation(NotNull.class));
    }

    private static DbTransactionController controller(IDbConnectionContextService service) throws Exception {
        DbTransactionController controller = new DbTransactionController();
        setField(controller, "connectionContextService", service);
        setField(controller, "dbWebConverter", Mappers.getMapper(DbWebConverter.class));
        return controller;
    }

    private static void assertOnlyPost(String methodName, Class<?> parameterType) throws Exception {
        PostMapping mapping = DbTransactionController.class
                .getDeclaredMethod(methodName, parameterType)
                .getAnnotation(PostMapping.class);
        assertArrayEquals(new String[]{"/" + methodName}, mapping.value());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record RecordingConnectionContextService(AtomicReference<DbConnectionContextRequest> received,
            RuntimeException failure) implements IDbConnectionContextService {

        private RecordingConnectionContextService(AtomicReference<DbConnectionContextRequest> received,
                RuntimeException failure) {
            this.received = received;
            this.failure = failure;
        }

        @Override
        public void bind(DbConnectionContextRequest dbConnectionContextRequest) {
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
        }

        @Override
        public void rebindCurrentDatabase(String databaseName) {
        }

        @Override
        public void close() {
        }

        @Override
        public TransactionStateResponse beginManualTransaction(DbConnectionContextRequest request) {
            return transaction(request);
        }

        @Override
        public TransactionStateResponse commitTransaction(DbConnectionContextRequest request) {
            return transaction(request);
        }

        @Override
        public TransactionStateResponse rollbackTransaction(DbConnectionContextRequest request) {
            return transaction(request);
        }

        @Override
        public TransactionStateResponse getTransactionState(DbConnectionContextRequest request) {
            return transaction(request);
        }

        @Override
        public TransactionStateResponse releaseBoundConnection(DbConnectionContextRequest request) {
            received.set(request);
            if (failure != null) {
                throw failure;
            }
            TransactionStateResponse response = TransactionStateResponse.of(false, TransactionMode.AUTO);
            response.setOutcome("ROLLED_BACK");
            return response;
        }

        @Override
        public boolean isInTransaction(Long consoleId) {
            return false;
        }

        @Override
        public <T> T withConsoleTransactionLock(Long consoleId, Callable<T> action) throws Exception {
            return action.call();
        }

        @Override
        public void releaseAllBoundTransactions() {
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
            return new DriverConfig();
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

        private TransactionStateResponse transaction(DbConnectionContextRequest request) {
            received.set(request);
            if (failure != null) {
                throw failure;
            }
            TransactionStateResponse response = TransactionStateResponse.of(false, TransactionMode.AUTO);
            response.setOutcome("COMMITTED");
            return response;
        }
    }
}
