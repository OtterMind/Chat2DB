package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.enums.datasource.MySqlTlsMode;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
        service = new DbWorkspaceDataSourceServiceImpl(storageFacade(), dataSourceService());
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

    @Test
    void preConnectRecoversSavedSslWhenRequestDoesNotResubmitIt() {
        // Saved row carries TLS material (public fields only — secret fields are blank so
        // decryptSensitiveFields is a no-op on them, keeping the test focused on recovery).
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        savedDataSource.setSsl(savedSsl(MySqlTlsMode.VERIFY_CA, "saved-ca-pem"));
        DbDataSourcePreConnectRequest request = new DbDataSourcePreConnectRequest();
        request.setId(1L);
        request.setAuthenticationType("PASSWORD");
        request.setPassword("  ");
        // ssl intentionally left null — simulates test-from-saved where the client omits TLS.

        service.preConnect(request);

        // The saved (decrypted) TLS material is recovered onto the forwarded request.
        SSLInfo forwarded = forwardedRequest.getSsl();
        assertNotNull(forwarded);
        assertEquals(MySqlTlsMode.VERIFY_CA.name(), forwarded.getTlsMode());
        assertEquals("saved-ca-pem", forwarded.getCaPem());
    }

    @Test
    void preConnectKeepsResubmittedSslIntact() {
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        savedDataSource.setSsl(savedSsl(MySqlTlsMode.VERIFY_CA, "saved-ca-pem"));
        DbDataSourcePreConnectRequest request = new DbDataSourcePreConnectRequest();
        request.setId(1L);
        request.setAuthenticationType("PASSWORD");
        request.setPassword("new-password");
        SSLInfo resubmitted = savedSsl(MySqlTlsMode.REQUIRED, "new-ca-pem");
        request.setSsl(resubmitted);

        service.preConnect(request);

        // The resubmitted TLS wins; the saved material is not substituted in.
        assertSame(resubmitted, forwardedRequest.getSsl());
        assertEquals(MySqlTlsMode.REQUIRED.name(), forwardedRequest.getSsl().getTlsMode());
    }

    @Test
    void preConnectTreatsWhitespaceOnlySslAsBlankAndRecovers() {
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        savedDataSource.setSsl(savedSsl(MySqlTlsMode.VERIFY_IDENTITY, "saved-ca-pem"));
        DbDataSourcePreConnectRequest request = new DbDataSourcePreConnectRequest();
        request.setId(1L);
        request.setAuthenticationType("PASSWORD");
        request.setPassword("  ");
        SSLInfo blank = new SSLInfo();
        blank.setTlsMode("   ");
        blank.setCaPem("  ");
        request.setSsl(blank);

        service.preConnect(request);

        assertEquals(MySqlTlsMode.VERIFY_IDENTITY.name(), forwardedRequest.getSsl().getTlsMode());
    }

    @Test
    void preConnectDoesNotRecoverSslWhenSavedHasNone() {
        // No TLS on either side: recovery is a no-op and ssl stays null.
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        DbDataSourcePreConnectRequest request = new DbDataSourcePreConnectRequest();
        request.setId(1L);
        request.setAuthenticationType("PASSWORD");
        request.setPassword("  ");

        service.preConnect(request);

        assertNull(forwardedRequest.getSsl());
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

    private static SSLInfo savedSsl(MySqlTlsMode mode, String caPem) {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode(mode.name());
        ssl.setCaPem(caPem);
        return ssl;
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
