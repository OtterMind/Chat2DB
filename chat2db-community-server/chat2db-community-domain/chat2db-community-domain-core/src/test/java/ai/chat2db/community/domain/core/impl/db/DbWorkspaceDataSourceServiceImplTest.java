package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.config.Environment;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.db.IDbDataSourceService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.tools.security.AesGcmUtil;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.PermissionDeniedBusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbWorkspaceDataSourceServiceImplTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final List<String> calls = new ArrayList<>();
    private List<WorkspaceDataSource> listedDataSources;
    private DbDataSourcePageQueryRequest capturedListRequest;
    private WorkspaceDataSource queriedDataSource;
    private DbDataSourcePreConnectRequest forwardedPreConnectRequest;
    private RuntimeException identityColorUpdateFailure;
    private DbWorkspaceDataSourceServiceImpl service;

    @BeforeEach
    void setUp() {
        System.setProperty(AesGcmUtil.KEY_PROPERTY, TEST_KEY);
        service = new DbWorkspaceDataSourceServiceImpl(storageFacade(), dataSourceService(), environmentEnricher());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(AesGcmUtil.KEY_PROPERTY);
    }

    @Test
    void createsThenReturnsDisplayDataSource() {
        WorkspaceDataSource input = dataSource(7L, "new datasource", null);
        queriedDataSource = dataSource(101L, "created datasource", null);

        WorkspaceDataSource result = service.createDataSource(input);

        assertEquals(List.of("storage.create", "storage.query:101:false"), calls);
        assertEquals(101L, result.getId());
        assertEquals("created datasource", result.getAlias());
        assertNotSame(queriedDataSource, result);
    }

    @Test
    void updatesThenClearsConnectionAndReturnsDisplayDataSource() {
        WorkspaceDataSource input = dataSource(12L, "updated datasource", null);
        queriedDataSource = dataSource(12L, "stored datasource", null);
        queriedDataSource.setIdentityColor("#112233");

        WorkspaceDataSource result = service.updateDataSource(input);

        assertEquals(List.of(
                "storage.update:12",
                "runtime.remove:12",
                "storage.query:12:false"), calls);
        assertEquals("stored datasource", result.getAlias());
        assertNull(input.getIdentityColor());
    }

    @Test
    void normalizesIdentityColorWithoutResettingConnection() {
        queriedDataSource = dataSource(52L, "stored datasource", null);

        WorkspaceDataSource result = service.updateDataSourceIdentityColor(52L, "  #a1b2c3  ");

        assertEquals(List.of(
                "storage.updateIdentityColor:52:#A1B2C3",
                "storage.query:52:false"), calls);
        assertEquals("#A1B2C3", result.getIdentityColor());
    }

    @Test
    void clearsIdentityColorWithNull() {
        queriedDataSource = dataSource(53L, "stored datasource", null);
        queriedDataSource.setIdentityColor("#445566");

        WorkspaceDataSource result = service.updateDataSourceIdentityColor(53L, null);

        assertEquals(List.of(
                "storage.updateIdentityColor:53:null",
                "storage.query:53:false"), calls);
        assertNull(result.getIdentityColor());
    }

    @Test
    void rejectsInvalidIdentityColorBeforeStorageAccess() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateDataSourceIdentityColor(54L, "#ABC"));

        assertEquals("datasource.identityColor.invalid", exception.getCode());
        assertEquals(List.of(), calls);
    }

    @Test
    void propagatesStoragePermissionFailureWithoutReadingBackData() {
        identityColorUpdateFailure = new PermissionDeniedBusinessException();

        assertThrows(PermissionDeniedBusinessException.class,
                () -> service.updateDataSourceIdentityColor(55L, "#ABCDEF"));

        assertEquals(List.of("storage.updateIdentityColor:55:#ABCDEF"), calls);
    }

    @Test
    void deletesThenClearsConnection() {
        service.deleteDataSource(23L);

        assertEquals(List.of("storage.delete:23", "runtime.remove:23"), calls);
    }

    @Test
    void preConnectRestoresSavedPasswordBeforeDelegating() {
        queriedDataSource = dataSource(34L, "saved datasource",
                AesGcmUtil.configured().encrypt("saved-password"));
        DbDataSourcePreConnectRequest request = new DbDataSourcePreConnectRequest();
        request.setId(34L);
        request.setAuthenticationType("PASSWORD");

        service.preConnect(request);

        assertEquals(List.of("storage.query:34:true", "runtime.preConnect"), calls);
        assertEquals("saved-password", request.getPassword());
        assertSame(request, forwardedPreConnectRequest);
    }

    @Test
    void displayCopyPreservesWorkspaceDataSourceSubtype() {
        ExtendedWorkspaceDataSource stored = new ExtendedWorkspaceDataSource();
        stored.setId(45L);
        stored.setAlias("extended datasource");
        stored.setStorageType("LOCAL");
        stored.setExtensionValue("enterprise-value");
        queriedDataSource = stored;

        WorkspaceDataSource result = service.queryDisplayDataSourceById(45L, false);

        ExtendedWorkspaceDataSource extended = assertInstanceOf(ExtendedWorkspaceDataSource.class, result);
        assertEquals("enterprise-value", extended.getExtensionValue());
        assertNotSame(stored, extended);
    }

    @Test
    void displayCopyFallsBackForSubtypeWithoutDefaultConstructor() {
        ConstructorOnlyWorkspaceDataSource stored = new ConstructorOnlyWorkspaceDataSource("extension");
        stored.setId(46L);
        stored.setAlias("constructor-only datasource");
        stored.setStorageType("LOCAL");
        queriedDataSource = stored;

        WorkspaceDataSource result = service.queryDisplayDataSourceById(46L, false);

        assertEquals(WorkspaceDataSource.class, result.getClass());
        assertEquals("constructor-only datasource", result.getAlias());
    }

    @Test
    void exportAllDataSourcesRequestsEveryRecord() {
        listedDataSources = new ArrayList<>();
        for (long id = 1; id <= 101; id++) {
            listedDataSources.add(dataSource(id, "datasource " + id, null));
        }
        queriedDataSource = listedDataSources.get(0);

        List<WorkspaceDataSource> result = service.exportDataSources(List.of());

        assertEquals(101, result.size());
        assertEquals(1, capturedListRequest.getPageNo());
        assertEquals(Integer.MAX_VALUE, capturedListRequest.getPageSize());
    }

    @Test
    void listHydratesCurrentEnvironmentWithoutMutatingStoredDataSource() {
        WorkspaceDataSource stored = dataSource(61L, "release datasource", null);
        stored.setEnvironmentId(2L);
        stored.setEnvironment(Environment.builder().id(2L).name("STALE").color("BLUE").build());
        listedDataSources = List.of(stored);
        DbDataSourcePageQueryRequest request = new DbDataSourcePageQueryRequest();
        request.setPageNo(1);
        request.setPageSize(10);

        WorkspaceDataSource result = service.listDataSources(request).getData().get(0);

        assertNotSame(stored, result);
        assertEquals("RELEASE", result.getEnvironment().getName());
        assertEquals("RED", result.getEnvironment().getColor());
        assertEquals("STALE", stored.getEnvironment().getName());
    }

    private IWorkspaceStorageFacade storageFacade() {
        return (IWorkspaceStorageFacade) Proxy.newProxyInstance(
                IWorkspaceStorageFacade.class.getClassLoader(),
                new Class<?>[]{IWorkspaceStorageFacade.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createDataSource" -> {
                        calls.add("storage.create");
                        yield 101L;
                    }
                    case "updateDataSource" -> {
                        WorkspaceDataSource dataSource = (WorkspaceDataSource) args[0];
                        calls.add("storage.update:" + dataSource.getId());
                        yield dataSource.getId();
                    }
                    case "updateDataSourceIdentityColor" -> {
                        calls.add("storage.updateIdentityColor:" + args[0] + ":" + args[1]);
                        if (identityColorUpdateFailure != null) {
                            throw identityColorUpdateFailure;
                        }
                        if (queriedDataSource != null) {
                            queriedDataSource.setIdentityColor((String) args[1]);
                        }
                        yield args[0];
                    }
                    case "deleteDataSource" -> {
                        calls.add("storage.delete:" + args[0]);
                        yield null;
                    }
                    case "queryDataSourceById" -> {
                        calls.add("storage.query:" + args[0] + ":" + args[1]);
                        yield queriedDataSource;
                    }
                    case "listDataSources" -> {
                        capturedListRequest = (DbDataSourcePageQueryRequest) args[0];
                        List<WorkspaceDataSource> dataSources = listedDataSources == null
                                ? List.of()
                                : listedDataSources;
                        yield PageResponse.of(dataSources, (long) dataSources.size(),
                                capturedListRequest.getPageNo(), capturedListRequest.getPageSize());
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private IDbDataSourceService dataSourceService() {
        return (IDbDataSourceService) Proxy.newProxyInstance(
                IDbDataSourceService.class.getClassLoader(),
                new Class<?>[]{IDbDataSourceService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "preConnect" -> {
                        calls.add("runtime.preConnect");
                        forwardedPreConnectRequest = (DbDataSourcePreConnectRequest) args[0];
                        yield null;
                    }
                    case "removeConnection" -> {
                        calls.add("runtime.remove:" + args[0]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private DataSourceEnvironmentEnricher environmentEnricher() {
        return new DataSourceEnvironmentEnricher(() -> List.of(
                Environment.builder().id(1L).name("TEST").shortName("Test Environment").color("GREEN").build(),
                Environment.builder().id(2L).name("RELEASE").shortName("Release Environment").color("RED").build()));
    }

    private static WorkspaceDataSource dataSource(Long id, String name, String password) {
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriverClass("test.Driver");

        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setId(id);
        dataSource.setAlias(name);
        dataSource.setPassword(password);
        dataSource.setStorageType("LOCAL");
        dataSource.setDriverConfig(driverConfig);
        return dataSource;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return 0;
    }

    public static class ExtendedWorkspaceDataSource extends WorkspaceDataSource {

        private String extensionValue;

        public String getExtensionValue() {
            return extensionValue;
        }

        public void setExtensionValue(String extensionValue) {
            this.extensionValue = extensionValue;
        }
    }

    public static class ConstructorOnlyWorkspaceDataSource extends WorkspaceDataSource {

        private final String extensionValue;

        public ConstructorOnlyWorkspaceDataSource(String extensionValue) {
            this.extensionValue = extensionValue;
        }

        public String getExtensionValue() {
            return extensionValue;
        }
    }
}
