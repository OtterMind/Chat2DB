package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbDatabaseService;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDatabaseControllerCharsetContextTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void charsetReadbackEndpointUsesDatasourceRequestContext() throws Exception {
        Method method = findControllerMethod("info");

        assertTrue(DataSourceBaseRequest.class.isAssignableFrom(method.getParameterTypes()[0]),
                "database charset readback must expose dataSourceId for ConnectionInfoAspect");
    }

    @Test
    void charsetPreviewEndpointUsesDatasourceRequestContext() throws Exception {
        Class<?> requestType = Class.forName(
                "ai.chat2db.community.web.api.controller.DbDatabaseController$AlterDatabasePreviewRequest");

        assertTrue(DataSourceBaseRequest.class.isAssignableFrom(requestType),
                "database charset preview must expose dataSourceId for ConnectionInfoAspect");
    }

    @Test
    void charsetRequestsRequireDatasourceContext() {
        DataSourceBaseRequest infoRequest = new DataSourceBaseRequest();
        infoRequest.setDatabaseName("app");
        assertEquals(1, validator.validate(infoRequest).size());

        DbDatabaseController.AlterDatabasePreviewRequest previewRequest =
                new DbDatabaseController.AlterDatabasePreviewRequest();
        previewRequest.setDatabaseName("app");
        previewRequest.setCharset("utf8mb4");
        assertEquals(1, validator.validate(previewRequest).size());
    }

    @Test
    void charsetReadbackPassesDatabaseNameToService() throws Exception {
        AtomicReference<Long> capturedDataSourceId = new AtomicReference<>();
        AtomicReference<String> capturedDatabase = new AtomicReference<>();
        IDbDatabaseService service = (IDbDatabaseService) Proxy.newProxyInstance(
                IDbDatabaseService.class.getClassLoader(),
                new Class<?>[]{IDbDatabaseService.class},
                (proxy, method, args) -> {
                    if ("databaseInfo".equals(method.getName())) {
                        capturedDataSourceId.set((Long) args[0]);
                        capturedDatabase.set((String) args[1]);
                        return Map.of("charset", "utf8mb4", "collation", "utf8mb4_0900_ai_ci");
                    }
                    return null;
                });
        DbDatabaseController controller = new DbDatabaseController();
        setField(controller, "databaseService", service);
        DataSourceBaseRequest request = new DataSourceBaseRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("tenant_a");

        findControllerMethod("info").invoke(controller, request);

        assertEquals(42L, capturedDataSourceId.get());
        assertEquals("tenant_a", capturedDatabase.get());
    }

    @Test
    void charsetPreviewPassesRequestedPropertiesToService() {
        AtomicReference<Long> capturedDataSourceId = new AtomicReference<>();
        AtomicReference<String> capturedDatabase = new AtomicReference<>();
        AtomicReference<String> capturedCharset = new AtomicReference<>();
        AtomicReference<String> capturedCollation = new AtomicReference<>();
        IDbDatabaseService service = (IDbDatabaseService) Proxy.newProxyInstance(
                IDbDatabaseService.class.getClassLoader(),
                new Class<?>[]{IDbDatabaseService.class},
                (proxy, method, args) -> {
                    if ("previewAlterDatabaseSql".equals(method.getName())) {
                        capturedDataSourceId.set((Long) args[0]);
                        capturedDatabase.set((String) args[1]);
                        capturedCharset.set((String) args[2]);
                        capturedCollation.set((String) args[3]);
                        return "ALTER DATABASE `tenant_a` DEFAULT COLLATE utf8mb4_bin";
                    }
                    return null;
                });
        DbDatabaseController controller = new DbDatabaseController();
        try {
            setField(controller, "databaseService", service);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        DbDatabaseController.AlterDatabasePreviewRequest request =
                new DbDatabaseController.AlterDatabasePreviewRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("tenant_a");
        request.setCharset("utf8mb4");
        request.setCollation("utf8mb4_bin");

        controller.alterPreview(request);

        assertEquals(42L, capturedDataSourceId.get());
        assertEquals("tenant_a", capturedDatabase.get());
        assertEquals("utf8mb4", capturedCharset.get());
        assertEquals("utf8mb4_bin", capturedCollation.get());
    }

    private static Method findControllerMethod(String name) {
        for (Method method : DbDatabaseController.class.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("Missing controller method: " + name);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
