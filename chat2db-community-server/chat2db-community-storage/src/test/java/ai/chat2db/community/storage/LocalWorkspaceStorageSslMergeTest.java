package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.enums.datasource.MySqlTlsMode;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.storage.converter.StorageConverterImpl;
import ai.chat2db.community.storage.small.DataSourceStorage;
import ai.chat2db.community.tools.security.AesGcmUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the TLS material merge/encrypt semantics in {@link LocalWorkspaceStorage}:
 * <ul>
 *   <li>create encrypts the secret fields and leaves public material cleartext;</li>
 *   <li>update preserves an omitted/redacted secret, clears an explicit blank secret, and
 *       re-encrypts a supplied replacement secret;</li>
 *   <li>public fields always follow the incoming value — an empty string clears the previous.</li>
 * </ul>
 *
 * <p>AES-GCM encryption is non-deterministic (random IV), so ciphertext is never compared by
 * value. Instead each secret is verified by round-tripping through {@code decrypt} and by
 * confirming it differs from the cleartext.
 *
 * <p>The merge helpers are private; they are exercised directly via reflection so the assertions
 * pin the contract without coupling to the file-backed {@code DataSourceStorage} round-trip.
 */
class LocalWorkspaceStorageSslMergeTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private LocalWorkspaceStorage storage;
    private AesGcmUtil cipher;

    @BeforeAll
    static void initHomeAndKey() {
        // Redirect ~/.chat2db* to a temp dir before any storage class loads.
        TestHome.init();
        // Bootstrap the AES key before AesGcmUtil.configured() is first called.
        System.setProperty(AesGcmUtil.KEY_PROPERTY, TEST_KEY);
    }

    @AfterAll
    static void clearKey() {
        System.clearProperty(AesGcmUtil.KEY_PROPERTY);
    }

    @BeforeEach
    void setUp() {
        storage = new LocalWorkspaceStorage(new StorageConverterImpl());
        cipher = AesGcmUtil.configured();
    }

    // ---- create: secrets encrypted, public material cleartext --------------------------

    @Test
    void createWithStoresClearsConflictingPemAndEncryptsStoreSecrets() throws Exception {
        SSLInfo ssl = fullSsl("new-key", "new-keypass", "trust-bytes", "trustpass", "AAAB", "storepass");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", ssl, null);

        assertEquals(MySqlTlsMode.VERIFY_IDENTITY.name(), result.getTlsMode());
        assertNull(result.getCaPem());
        assertNull(result.getClientCertPem());
        assertEquals("JKS", result.getTrustStoreType());
        assertEquals("PKCS12", result.getKeyStoreType());
        assertNull(result.getClientPrivateKeyPem());
        assertNull(result.getClientKeyPassword());
        assertEncrypted("trust-bytes", result.getTrustStoreBytes());
        assertEncrypted("trustpass", result.getTrustStorePassword());
        assertEncrypted("AAAB", result.getKeyStoreBytes());
        assertEncrypted("storepass", result.getKeyStorePassword());
    }

    @Test
    void createWithBlankSecretsKeepsThemNull() throws Exception {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode(MySqlTlsMode.REQUIRED.name());
        // no secret fields set

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", ssl, null);

        assertEquals(MySqlTlsMode.REQUIRED.name(), result.getTlsMode());
        assertNull(result.getClientPrivateKeyPem());
        assertNull(result.getClientKeyPassword());
        assertNull(result.getTrustStoreBytes());
        assertNull(result.getTrustStorePassword());
        assertNull(result.getKeyStoreBytes());
        assertNull(result.getKeyStorePassword());
    }

    // ---- update: omitted preserves, blank clears, supplied replaces --------------------

    @Test
    void updatePreservesPreviousSecretWhenIncomingOmitted() throws Exception {
        // Previously-saved row: secrets already encrypted at rest.
        String oldKeyCipher = cipher.encrypt("old-key");
        String oldKeyPassCipher = cipher.encrypt("old-keypass");
        String oldTrustBytesCipher = cipher.encrypt("old-trust-bytes");
        String oldTrustPassCipher = cipher.encrypt("old-trustpass");
        String oldBytesCipher = cipher.encrypt("old-bytes");
        String oldStorePassCipher = cipher.encrypt("old-storepass");
        SSLInfo oldEncrypted = fullSsl(oldKeyCipher, oldKeyPassCipher, oldTrustBytesCipher, oldTrustPassCipher,
                oldBytesCipher, oldStorePassCipher);

        // Incoming edit resubmits only public fields, as display/redacted forms do.
        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.REQUIRED.name());

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        // Public fields follow the incoming value.
        assertEquals(MySqlTlsMode.REQUIRED.name(), result.getTlsMode());
        // Omitted secrets preserve saved ciphertext exactly.
        assertEquals(oldKeyCipher, result.getClientPrivateKeyPem());
        assertEquals(oldKeyPassCipher, result.getClientKeyPassword());
        assertEquals(oldTrustBytesCipher, result.getTrustStoreBytes());
        assertEquals(oldTrustPassCipher, result.getTrustStorePassword());
        assertEquals(oldBytesCipher, result.getKeyStoreBytes());
        assertEquals(oldStorePassCipher, result.getKeyStorePassword());
    }

    @Test
    void updatePemSourcesClearSavedTrustAndClientStores() throws Exception {
        SSLInfo oldEncrypted = new SSLInfo();
        oldEncrypted.setTlsMode(MySqlTlsMode.VERIFY_CA.name());
        oldEncrypted.setTrustStoreType("JKS");
        oldEncrypted.setTrustStoreBytes(cipher.encrypt("old-trust-store"));
        oldEncrypted.setTrustStorePassword(cipher.encrypt("old-trust-password"));
        oldEncrypted.setKeyStoreType("PKCS12");
        oldEncrypted.setKeyStoreBytes(cipher.encrypt("old-client-store"));
        oldEncrypted.setKeyStorePassword(cipher.encrypt("old-client-password"));

        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.VERIFY_IDENTITY.name());
        incoming.setCaPem("new-ca-pem");
        incoming.setClientCertPem("new-client-cert-pem");
        incoming.setClientPrivateKeyPem("new-client-key-pem");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        assertEquals("new-ca-pem", result.getCaPem());
        assertEquals("new-client-cert-pem", result.getClientCertPem());
        assertEncrypted("new-client-key-pem", result.getClientPrivateKeyPem());
        assertNull(result.getTrustStoreType());
        assertNull(result.getTrustStoreBytes());
        assertNull(result.getTrustStorePassword());
        assertNull(result.getKeyStoreType());
        assertNull(result.getKeyStoreBytes());
        assertNull(result.getKeyStorePassword());
    }

    @Test
    void updateStoreSourcesClearSavedTrustAndClientPem() throws Exception {
        SSLInfo oldEncrypted = new SSLInfo();
        oldEncrypted.setTlsMode(MySqlTlsMode.VERIFY_IDENTITY.name());
        oldEncrypted.setCaPem("old-ca-pem");
        oldEncrypted.setClientCertPem("old-client-cert-pem");
        oldEncrypted.setClientPrivateKeyPem(cipher.encrypt("old-client-key-pem"));
        oldEncrypted.setClientKeyPassword(cipher.encrypt("old-key-password"));

        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.VERIFY_CA.name());
        incoming.setTrustStoreType("JKS");
        incoming.setTrustStoreBytes("new-trust-store");
        incoming.setKeyStoreType("PKCS12");
        incoming.setKeyStoreBytes("new-client-store");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        assertNull(result.getCaPem());
        assertNull(result.getClientCertPem());
        assertNull(result.getClientPrivateKeyPem());
        assertNull(result.getClientKeyPassword());
        assertEncrypted("new-trust-store", result.getTrustStoreBytes());
        assertEncrypted("new-client-store", result.getKeyStoreBytes());
    }

    @Test
    void updateClearsPreviousSecretWhenIncomingBlank() throws Exception {
        SSLInfo oldEncrypted = fullSsl(
                cipher.encrypt("old-key"), cipher.encrypt("old-keypass"),
                cipher.encrypt("old-trust-bytes"), cipher.encrypt("old-trustpass"),
                cipher.encrypt("old-bytes"), cipher.encrypt("old-storepass"));

        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.REQUIRED.name());
        incoming.setClientPrivateKeyPem("");
        incoming.setClientKeyPassword("");
        incoming.setTrustStoreBytes("");
        incoming.setTrustStorePassword("");
        incoming.setKeyStoreBytes("");
        incoming.setKeyStorePassword("");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        assertNull(result.getClientPrivateKeyPem());
        assertNull(result.getClientKeyPassword());
        assertNull(result.getTrustStoreBytes());
        assertNull(result.getTrustStorePassword());
        assertNull(result.getKeyStoreBytes());
        assertNull(result.getKeyStorePassword());
    }

    @Test
    void updateReplacesStoreSecretsWhenIncomingSupplied() throws Exception {
        SSLInfo oldEncrypted = fullSsl(cipher.encrypt("old-key"), cipher.encrypt("old-keypass"),
                cipher.encrypt("old-trust-bytes"), cipher.encrypt("old-trustpass"),
                cipher.encrypt("old-bytes"), cipher.encrypt("old-storepass"));

        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.VERIFY_CA.name());
        incoming.setTrustStoreType("JKS");
        incoming.setTrustStoreBytes("new-trust-bytes");
        incoming.setTrustStorePassword("new-trustpass");
        incoming.setKeyStoreType("PKCS12");
        incoming.setKeyStoreBytes("BBBC");
        incoming.setKeyStorePassword("new-storepass");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        assertNull(result.getClientPrivateKeyPem());
        assertNull(result.getClientKeyPassword());
        assertEncrypted("new-trust-bytes", result.getTrustStoreBytes());
        assertEncrypted("new-trustpass", result.getTrustStorePassword());
        assertEncrypted("BBBC", result.getKeyStoreBytes());
        assertEncrypted("new-storepass", result.getKeyStorePassword());
        assertEquals(MySqlTlsMode.VERIFY_CA.name(), result.getTlsMode());
    }

    @Test
    void updateEmptyStringClearsPublicMaterial() throws Exception {
        String oldKeyCipher = cipher.encrypt("old-key");
        SSLInfo oldEncrypted = fullSsl(oldKeyCipher, cipher.encrypt("old-keypass"),
                cipher.encrypt("old-trust-bytes"), cipher.encrypt("old-trustpass"),
                cipher.encrypt("old-bytes"), cipher.encrypt("old-storepass"));

        // User cleared the CA, client cert, and secret material.
        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.REQUIRED.name());
        incoming.setCaPem("");
        incoming.setClientCertPem("");
        incoming.setClientPrivateKeyPem("");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        assertEquals("", result.getCaPem());
        assertEquals("", result.getClientCertPem());
        assertEquals(MySqlTlsMode.REQUIRED.name(), result.getTlsMode());
        assertNull(result.getClientPrivateKeyPem());
    }

    @Test
    void updateBlankKeyStoreTypeClearsIt() throws Exception {
        // Public keystore type: incoming wins, so blank clears it (mirrors CA/cert behaviour).
        SSLInfo oldEncrypted = fullSsl(
                cipher.encrypt("k"), cipher.encrypt("kp"), cipher.encrypt("tb"), cipher.encrypt("tp"),
                cipher.encrypt("kb"), cipher.encrypt("sp"));

        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.REQUIRED.name());
        incoming.setKeyStoreType("");
        incoming.setTrustStoreType("");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        assertEquals("", result.getKeyStoreType());
        assertEquals("", result.getTrustStoreType());
    }

    // ---- null handling ----------------------------------------------------------------

    @Test
    void incomingNullClearsOldEncrypted() throws Exception {
        SSLInfo oldEncrypted = fullSsl(
                cipher.encrypt("k"), cipher.encrypt("kp"), cipher.encrypt("tb"), cipher.encrypt("tp"),
                cipher.encrypt("kb"), cipher.encrypt("sp"));

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", null, oldEncrypted);

        assertNull(result);
    }

    @Test
    void bothNullReturnsNull() throws Exception {
        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", null, null);
        assertNull(result);
    }

    @Test
    void encryptSslSensitiveFieldsIsNoOpOnNull() throws Exception {
        // Null SSL must not throw.
        invoke("encryptSslSensitiveFields", (Object) null);
    }

    @Test
    void encryptSslSensitiveFieldsSkipsBlankValues() throws Exception {
        SSLInfo ssl = new SSLInfo();
        ssl.setClientPrivateKeyPem("real-key");
        // other secret fields left null/blank

        invoke("encryptSslSensitiveFields", ssl);

        assertEncrypted("real-key", ssl.getClientPrivateKeyPem());
        assertNull(ssl.getClientKeyPassword());
        assertNull(ssl.getTrustStoreBytes());
        assertNull(ssl.getTrustStorePassword());
        assertNull(ssl.getKeyStoreBytes());
        assertNull(ssl.getKeyStorePassword());
    }

    @Test
    void updateRoundTripPreservesOmittedSecretsAndClearsExplicitBlankSecrets() {
        SSLInfo stores = new SSLInfo();
        stores.setTlsMode(MySqlTlsMode.VERIFY_CA.name());
        stores.setTrustStoreType("JKS");
        stores.setTrustStoreBytes("saved-trust-bytes");
        stores.setTrustStorePassword("saved-trustpass");
        stores.setKeyStoreType("PKCS12");
        stores.setKeyStoreBytes("saved-store-bytes");
        stores.setKeyStorePassword("saved-storepass");
        WorkspaceDataSource created = dataSource(stores);
        Long id = storage.createDataSource(created);
        try {
            SSLInfo saved = storage.queryDataSourceById(id, true).getSsl();
            String encryptedTrustStoreBytes = saved.getTrustStoreBytes();
            String encryptedClientStoreBytes = saved.getKeyStoreBytes();
            assertEncrypted("saved-trust-bytes", encryptedTrustStoreBytes);
            assertEncrypted("saved-store-bytes", encryptedClientStoreBytes);

            WorkspaceDataSource unrelatedEdit = dataSource(new SSLInfo());
            unrelatedEdit.setId(id);
            unrelatedEdit.setAlias("renamed");
            unrelatedEdit.getSsl().setTlsMode(MySqlTlsMode.VERIFY_CA.name());
            unrelatedEdit.getSsl().setTrustStoreType("JKS");
            unrelatedEdit.getSsl().setKeyStoreType("PKCS12");
            storage.updateDataSource(unrelatedEdit);

            SSLInfo preserved = storage.queryDataSourceById(id, true).getSsl();
            assertEquals(encryptedTrustStoreBytes, preserved.getTrustStoreBytes());
            assertEquals(encryptedClientStoreBytes, preserved.getKeyStoreBytes());

            WorkspaceDataSource clearEdit = dataSource(new SSLInfo());
            clearEdit.setId(id);
            clearEdit.getSsl().setTlsMode(MySqlTlsMode.REQUIRED.name());
            clearEdit.getSsl().setTrustStoreBytes("");
            clearEdit.getSsl().setKeyStoreBytes("");
            storage.updateDataSource(clearEdit);

            SSLInfo cleared = storage.queryDataSourceById(id, true).getSsl();
            assertNull(cleared.getTrustStoreBytes());
            assertNull(cleared.getKeyStoreBytes());
        } finally {
            DataSourceStorage.INSTANCE.delete(id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    /** Asserts {@code ciphertext} decrypts to {@code expected} and is not the cleartext itself. */
    private void assertEncrypted(String expected, String ciphertext) {
        assertNotNull(ciphertext);
        assertNotEquals(expected, ciphertext);
        assertEquals(expected, cipher.decrypt(ciphertext));
    }

    private static SSLInfo fullSsl(String privateKey, String keyPassword,
                                   String trustStoreBytes, String trustStorePassword,
                                   String keyStoreBytes, String keyStorePassword) {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode(MySqlTlsMode.VERIFY_IDENTITY.name());
        ssl.setCaPem("ca-pem");
        ssl.setClientCertPem("client-cert-pem");
        ssl.setTrustStoreType("JKS");
        ssl.setTrustStoreBytes(trustStoreBytes);
        ssl.setTrustStorePassword(trustStorePassword);
        ssl.setKeyStoreType("PKCS12");
        ssl.setClientPrivateKeyPem(privateKey);
        ssl.setClientKeyPassword(keyPassword);
        ssl.setKeyStoreBytes(keyStoreBytes);
        ssl.setKeyStorePassword(keyStorePassword);
        return ssl;
    }

    private static WorkspaceDataSource dataSource(SSLInfo ssl) {
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setAlias("mysql");
        dataSource.setType("MYSQL");
        dataSource.setHost("localhost");
        dataSource.setPort("3306");
        dataSource.setPassword("password");
        dataSource.setSsl(ssl);
        return dataSource;
    }

    private Object invoke(String methodName, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? SSLInfo.class : args[i].getClass();
        }
        Method m = LocalWorkspaceStorage.class.getDeclaredMethod(methodName, types);
        m.setAccessible(true);
        try {
            return m.invoke(storage, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : e;
        }
    }
}
