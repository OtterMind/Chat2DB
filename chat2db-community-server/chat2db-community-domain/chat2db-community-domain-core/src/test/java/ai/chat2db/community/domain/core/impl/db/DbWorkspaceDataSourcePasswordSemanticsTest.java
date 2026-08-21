package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.db.IDbDataSourceService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.tools.security.AesGcmUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DbWorkspaceDataSourcePasswordSemanticsTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private WorkspaceDataSource savedDataSource;
    private DbDataSourcePreConnectRequest forwardedRequest;
    private DbWorkspaceDataSourceServiceImpl service;

    @BeforeEach
    void setUp() {
        System.setProperty(AesGcmUtil.KEY_PROPERTY, TEST_KEY);
        System.setProperty("chat2db.runtime.mode", "community");
        service = new DbWorkspaceDataSourceServiceImpl(storageFacade(), dataSourceService(),
                new DataSourceEnvironmentEnricher(List::of));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(AesGcmUtil.KEY_PROPERTY);
        System.clearProperty("chat2db.runtime.mode");
    }

    @Test
    void preConnectPreservesWhitespacePassword() {
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        DbDataSourcePreConnectRequest request = new DbDataSourcePreConnectRequest();
        request.setId(1L);
        request.setAuthenticationType("PASSWORD");
        request.setPassword("  ");

        service.preConnect(request);

        assertEquals("  ", forwardedRequest.getPassword());
    }

    @Test
    void communityExportOmitsPassword() {
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));

        List<WorkspaceDataSource> exported = service.exportDisplayDataSources(List.of(1L));

        assertEquals(1, exported.size());
        assertNull(exported.get(0).getPassword());
    }

    private IWorkspaceStorageFacade storageFacade() {
        return (IWorkspaceStorageFacade) Proxy.newProxyInstance(
                IWorkspaceStorageFacade.class.getClassLoader(),
                new Class<?>[]{IWorkspaceStorageFacade.class},
                (proxy, method, args) -> {
                    if ("queryDataSourceById".equals(method.getName())) {
                        return savedDataSource;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private IDbDataSourceService dataSourceService() {
        return (IDbDataSourceService) Proxy.newProxyInstance(
                IDbDataSourceService.class.getClassLoader(),
                new Class<?>[]{IDbDataSourceService.class},
                (proxy, method, args) -> {
                    if ("preConnect".equals(method.getName())) {
                        forwardedRequest = (DbDataSourcePreConnectRequest) args[0];
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static WorkspaceDataSource localDataSource(String password) {
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setId(1L);
        dataSource.setStorageType("LOCAL");
        dataSource.setPassword(password);
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
}
