package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.enums.datasource.MySqlTlsMode;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import ai.chat2db.community.storage.converter.StorageConverterImpl;
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
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the TLS material merge/encrypt semantics in {@link LocalWorkspaceStorage}:
 * <ul>
 *   <li>create encrypts the secret fields and leaves public material cleartext;</li>
 *   <li>update preserves a blank secret (keeps the previous ciphertext, so the user need not
 *       re-upload the key on every edit) and re-encrypts a supplied secret;</li>
 *   <li>public fields always follow the incoming value — an empty string clears the previous.</li>
 * </ul>
 *
 * <p>AES-GCM encryption is non-deterministic (random IV), so ciphertext is never compared by
 * value. Instead each secret is verified by round-tripping through {@code decrypt} and by
 * confirming it differs from the cleartext; the "preserve" path additionally checks the previous
 * ciphertext is returned byte-for-byte (no decrypt+reencrypt).
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
    void createEncryptsSecretsAndLeavesPublicMaterialCleartext() throws Exception {
        SSLInfo ssl = fullSsl("new-key", "new-keypass", "AAAB", "storepass");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", ssl, null);

        // Public material is stored verbatim.
        assertEquals(MySqlTlsMode.VERIFY_IDENTITY.name(), result.getTlsMode());
        assertEquals("ca-pem", result.getCaPem());
        assertEquals("client-cert-pem", result.getClientCertPem());
        assertEquals("PKCS12", result.getKeyStoreType());
        // Secrets are encrypted: round-trips to the original and differs from the cleartext.
        assertEncrypted("new-key", result.getClientPrivateKeyPem());
        assertEncrypted("new-keypass", result.getClientKeyPassword());
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
        assertNull(result.getKeyStoreBytes());
        assertNull(result.getKeyStorePassword());
    }

    // ---- update: blank secret preserves previous ciphertext ---------------------------

    @Test
    void updatePreservesPreviousSecretWhenIncomingBlank() throws Exception {
        // Previously-saved row: secrets already encrypted at rest.
        String oldKeyCipher = cipher.encrypt("old-key");
        String oldKeyPassCipher = cipher.encrypt("old-keypass");
        String oldBytesCipher = cipher.encrypt("old-bytes");
        String oldStorePassCipher = cipher.encrypt("old-storepass");
        SSLInfo oldEncrypted = fullSsl(oldKeyCipher, oldKeyPassCipher, oldBytesCipher, oldStorePassCipher);

        // Incoming edit resubmits mode + CA but blanks the secrets (user did not re-upload).
        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.REQUIRED.name());
        incoming.setCaPem("new-ca-pem");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        // Public fields follow the incoming value.
        assertEquals(MySqlTlsMode.REQUIRED.name(), result.getTlsMode());
        assertEquals("new-ca-pem", result.getCaPem());
        // Secrets unchanged: the previous ciphertext is preserved verbatim (no decrypt+reencrypt).
        assertEquals(oldKeyCipher, result.getClientPrivateKeyPem());
        assertEquals(oldKeyPassCipher, result.getClientKeyPassword());
        assertEquals(oldBytesCipher, result.getKeyStoreBytes());
        assertEquals(oldStorePassCipher, result.getKeyStorePassword());
        // And it still round-trips to the original secret.
        assertEquals("old-key", cipher.decrypt(result.getClientPrivateKeyPem()));
    }

    @Test
    void updateReplacesSecretWhenIncomingSupplied() throws Exception {
        String oldKeyCipher = cipher.encrypt("old-key");
        SSLInfo oldEncrypted = fullSsl(oldKeyCipher, cipher.encrypt("old-keypass"),
                cipher.encrypt("old-bytes"), cipher.encrypt("old-storepass"));

        SSLInfo incoming = fullSsl("rotated-key", "rotated-keypass", "BBBC", "new-storepass");
        incoming.setTlsMode(MySqlTlsMode.VERIFY_CA.name());

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        assertEncrypted("rotated-key", result.getClientPrivateKeyPem());
        assertEncrypted("rotated-keypass", result.getClientKeyPassword());
        assertEncrypted("BBBC", result.getKeyStoreBytes());
        assertEncrypted("new-storepass", result.getKeyStorePassword());
        // The new ciphertext is not the old one.
        assertNotEquals(oldKeyCipher, result.getClientPrivateKeyPem());
        assertEquals(MySqlTlsMode.VERIFY_CA.name(), result.getTlsMode());
    }

    @Test
    void updateEmptyStringClearsPublicMaterial() throws Exception {
        String oldKeyCipher = cipher.encrypt("old-key");
        SSLInfo oldEncrypted = fullSsl(oldKeyCipher, cipher.encrypt("old-keypass"),
                cipher.encrypt("old-bytes"), cipher.encrypt("old-storepass"));

        // User cleared the CA and client cert (and switched mode down) but left secrets blank.
        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.REQUIRED.name());
        incoming.setCaPem("");
        incoming.setClientCertPem("");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        assertEquals("", result.getCaPem());
        assertEquals("", result.getClientCertPem());
        assertEquals(MySqlTlsMode.REQUIRED.name(), result.getTlsMode());
        // Secrets still preserved.
        assertEquals(oldKeyCipher, result.getClientPrivateKeyPem());
    }

    @Test
    void updateBlankKeyStoreTypeClearsIt() throws Exception {
        // Public keystore type: incoming wins, so blank clears it (mirrors CA/cert behaviour).
        SSLInfo oldEncrypted = fullSsl(
                cipher.encrypt("k"), cipher.encrypt("kp"),
                cipher.encrypt("kb"), cipher.encrypt("sp"));

        SSLInfo incoming = new SSLInfo();
        incoming.setTlsMode(MySqlTlsMode.REQUIRED.name());
        incoming.setKeyStoreType("");

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", incoming, oldEncrypted);

        assertEquals("", result.getKeyStoreType());
    }

    // ---- null handling ----------------------------------------------------------------

    @Test
    void incomingNullReturnsOldEncrypted() throws Exception {
        SSLInfo oldEncrypted = fullSsl(
                cipher.encrypt("k"), cipher.encrypt("kp"),
                cipher.encrypt("kb"), cipher.encrypt("sp"));

        SSLInfo result = (SSLInfo) invoke("mergeAndEncryptSsl", null, oldEncrypted);

        assertSame(oldEncrypted, result);
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
        assertNull(ssl.getKeyStoreBytes());
        assertNull(ssl.getKeyStorePassword());
    }

    // ---- helpers ----------------------------------------------------------------------

    /** Asserts {@code ciphertext} decrypts to {@code expected} and is not the cleartext itself. */
    private void assertEncrypted(String expected, String ciphertext) {
        assertNotNull(ciphertext);
        assertNotEquals(expected, ciphertext);
        assertEquals(expected, cipher.decrypt(ciphertext));
    }

    private static SSLInfo fullSsl(String privateKey, String keyPassword,
                                   String keyStoreBytes, String keyStorePassword) {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode(MySqlTlsMode.VERIFY_IDENTITY.name());
        ssl.setCaPem("ca-pem");
        ssl.setClientCertPem("client-cert-pem");
        ssl.setKeyStoreType("PKCS12");
        ssl.setClientPrivateKeyPem(privateKey);
        ssl.setClientKeyPassword(keyPassword);
        ssl.setKeyStoreBytes(keyStoreBytes);
        ssl.setKeyStorePassword(keyStorePassword);
        return ssl;
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
