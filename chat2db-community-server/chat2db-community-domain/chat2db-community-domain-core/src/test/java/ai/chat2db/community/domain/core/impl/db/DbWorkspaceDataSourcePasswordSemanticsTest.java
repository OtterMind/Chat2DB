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

    @Test
    void queryDisplayRedactsTlsSecretsWithoutMutatingSavedDataSource() {
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        savedDataSource.setSsl(savedSsl(MySqlTlsMode.VERIFY_IDENTITY, "saved-ca-pem"));
        savedDataSource.getSsl().setClientPrivateKeyPem(AesGcmUtil.configured().encrypt("saved-client-key"));
        savedDataSource.getSsl().setTrustStoreBytes(AesGcmUtil.configured().encrypt("saved-trust-store"));
        savedDataSource.getSsl().setKeyStoreBytes(AesGcmUtil.configured().encrypt("saved-client-store"));
        savedDataSource.getSsl().setClientPrivateKeyPem("encrypted-key");
        savedDataSource.getSsl().setClientKeyPassword("encrypted-key-password");
        savedDataSource.getSsl().setTrustStoreType("JKS");
        savedDataSource.getSsl().setTrustStoreBytes("encrypted-trust-store-bytes");
        savedDataSource.getSsl().setTrustStorePassword("encrypted-trust-store-password");
        savedDataSource.getSsl().setKeyStoreBytes("encrypted-store-bytes");
        savedDataSource.getSsl().setKeyStorePassword("encrypted-store-password");

        WorkspaceDataSource display = service.queryDisplayDataSourceById(1L, false);

        assertNotNull(display.getSsl());
        assertEquals(MySqlTlsMode.VERIFY_IDENTITY.name(), display.getSsl().getTlsMode());
        assertEquals("saved-ca-pem", display.getSsl().getCaPem());
        assertNull(display.getSsl().getClientPrivateKeyPem());
        assertNull(display.getSsl().getClientKeyPassword());
        assertEquals("JKS", display.getSsl().getTrustStoreType());
        assertNull(display.getSsl().getTrustStoreBytes());
        assertNull(display.getSsl().getTrustStorePassword());
        assertNull(display.getSsl().getKeyStoreBytes());
        assertNull(display.getSsl().getKeyStorePassword());
        assertEquals("encrypted-key", savedDataSource.getSsl().getClientPrivateKeyPem());
        assertEquals("encrypted-key-password", savedDataSource.getSsl().getClientKeyPassword());
        assertEquals("encrypted-trust-store-bytes", savedDataSource.getSsl().getTrustStoreBytes());
        assertEquals("encrypted-trust-store-password", savedDataSource.getSsl().getTrustStorePassword());
        assertEquals("encrypted-store-bytes", savedDataSource.getSsl().getKeyStoreBytes());
        assertEquals("encrypted-store-password", savedDataSource.getSsl().getKeyStorePassword());
    }

    @Test
    void communityExportRedactsTlsSecretsWithoutMutatingSavedDataSource() {
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        savedDataSource.setSsl(savedSsl(MySqlTlsMode.VERIFY_CA, "saved-ca-pem"));
        savedDataSource.getSsl().setClientPrivateKeyPem("encrypted-key");
        savedDataSource.getSsl().setClientKeyPassword("encrypted-key-password");
        savedDataSource.getSsl().setTrustStoreType("PKCS12");
        savedDataSource.getSsl().setTrustStoreBytes("encrypted-trust-store-bytes");
        savedDataSource.getSsl().setTrustStorePassword("encrypted-trust-store-password");
        savedDataSource.getSsl().setKeyStoreBytes("encrypted-store-bytes");
        savedDataSource.getSsl().setKeyStorePassword("encrypted-store-password");

        List<WorkspaceDataSource> exported = service.exportDisplayDataSources(List.of(1L));

        assertEquals(1, exported.size());
        SSLInfo ssl = exported.get(0).getSsl();
        assertNotNull(ssl);
        assertEquals(MySqlTlsMode.VERIFY_CA.name(), ssl.getTlsMode());
        assertEquals("saved-ca-pem", ssl.getCaPem());
        assertNull(ssl.getClientPrivateKeyPem());
        assertNull(ssl.getClientKeyPassword());
        assertEquals("PKCS12", ssl.getTrustStoreType());
        assertNull(ssl.getTrustStoreBytes());
        assertNull(ssl.getTrustStorePassword());
        assertNull(ssl.getKeyStoreBytes());
        assertNull(ssl.getKeyStorePassword());
        assertEquals("encrypted-key", savedDataSource.getSsl().getClientPrivateKeyPem());
        assertEquals("encrypted-key-password", savedDataSource.getSsl().getClientKeyPassword());
        assertEquals("encrypted-trust-store-bytes", savedDataSource.getSsl().getTrustStoreBytes());
        assertEquals("encrypted-trust-store-password", savedDataSource.getSsl().getTrustStorePassword());
        assertEquals("encrypted-store-bytes", savedDataSource.getSsl().getKeyStoreBytes());
        assertEquals("encrypted-store-password", savedDataSource.getSsl().getKeyStorePassword());
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
    void preConnectRecoversOmittedPemSecretsFromRedactedSsl() {
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        SSLInfo saved = savedSsl(MySqlTlsMode.VERIFY_IDENTITY, "saved-ca-pem");
        saved.setClientCertPem("saved-client-cert-pem");
        saved.setClientPrivateKeyPem(AesGcmUtil.configured().encrypt("saved-client-key-pem"));
        saved.setClientKeyPassword(AesGcmUtil.configured().encrypt("saved-key-password"));
        savedDataSource.setSsl(saved);

        DbDataSourcePreConnectRequest request = requestWithSsl(savedSsl(
                MySqlTlsMode.VERIFY_IDENTITY, "saved-ca-pem"));
        request.getSsl().setClientCertPem("saved-client-cert-pem");

        service.preConnect(request);

        assertEquals("saved-client-key-pem", forwardedRequest.getSsl().getClientPrivateKeyPem());
        assertEquals("saved-key-password", forwardedRequest.getSsl().getClientKeyPassword());
        assertNull(forwardedRequest.getSsl().getTrustStoreBytes());
        assertNull(forwardedRequest.getSsl().getKeyStoreBytes());
    }

    @Test
    void preConnectRecoversOmittedTrustAndClientStoreSecretsFromRedactedSsl() {
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        SSLInfo saved = savedSsl(MySqlTlsMode.VERIFY_CA, null);
        saved.setTrustStoreType("JKS");
        saved.setTrustStoreBytes(AesGcmUtil.configured().encrypt("saved-trust-store"));
        saved.setTrustStorePassword(AesGcmUtil.configured().encrypt("saved-trust-password"));
        saved.setKeyStoreType("PKCS12");
        saved.setKeyStoreBytes(AesGcmUtil.configured().encrypt("saved-client-store"));
        saved.setKeyStorePassword(AesGcmUtil.configured().encrypt("saved-client-password"));
        savedDataSource.setSsl(saved);

        SSLInfo redacted = savedSsl(MySqlTlsMode.VERIFY_CA, null);
        redacted.setTrustStoreType("JKS");
        redacted.setKeyStoreType("PKCS12");
        DbDataSourcePreConnectRequest request = requestWithSsl(redacted);

        service.preConnect(request);

        assertEquals("saved-trust-store", forwardedRequest.getSsl().getTrustStoreBytes());
        assertEquals("saved-trust-password", forwardedRequest.getSsl().getTrustStorePassword());
        assertEquals("saved-client-store", forwardedRequest.getSsl().getKeyStoreBytes());
        assertEquals("saved-client-password", forwardedRequest.getSsl().getKeyStorePassword());
        assertNull(forwardedRequest.getSsl().getCaPem());
        assertNull(forwardedRequest.getSsl().getClientCertPem());
    }

    @Test
    void preConnectPreservesExplicitBlankSecretClear() {
        savedDataSource = localDataSource(AesGcmUtil.configured().encrypt("saved-password"));
        SSLInfo saved = savedSsl(MySqlTlsMode.VERIFY_IDENTITY, "saved-ca-pem");
        saved.setClientCertPem("saved-client-cert-pem");
        saved.setClientPrivateKeyPem(AesGcmUtil.configured().encrypt("saved-client-key-pem"));
        saved.setClientKeyPassword(AesGcmUtil.configured().encrypt("saved-key-password"));
        savedDataSource.setSsl(saved);

        SSLInfo cleared = savedSsl(MySqlTlsMode.VERIFY_IDENTITY, "saved-ca-pem");
        cleared.setClientCertPem("saved-client-cert-pem");
        cleared.setClientPrivateKeyPem("");
        cleared.setClientKeyPassword("");
        DbDataSourcePreConnectRequest request = requestWithSsl(cleared);

        service.preConnect(request);

        assertEquals("", forwardedRequest.getSsl().getClientPrivateKeyPem());
        assertEquals("", forwardedRequest.getSsl().getClientKeyPassword());
    }

    @Test
    void preConnectPreservesExplicitBlankSslInsteadOfRecoveringSavedTls() {
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

        assertSame(blank, forwardedRequest.getSsl());
        assertEquals("   ", forwardedRequest.getSsl().getTlsMode());
        assertNull(forwardedRequest.getSsl().getClientPrivateKeyPem());
        assertNull(forwardedRequest.getSsl().getTrustStoreBytes());
        assertNull(forwardedRequest.getSsl().getKeyStoreBytes());
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

    private static DbDataSourcePreConnectRequest requestWithSsl(SSLInfo ssl) {
        DbDataSourcePreConnectRequest request = new DbDataSourcePreConnectRequest();
        request.setId(1L);
        request.setAuthenticationType("PASSWORD");
        request.setPassword("  ");
        request.setSsl(ssl);
        return request;
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
