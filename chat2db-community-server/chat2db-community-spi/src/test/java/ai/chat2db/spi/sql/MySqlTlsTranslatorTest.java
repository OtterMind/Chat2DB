package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.datasource.MySqlTlsMode;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link MySqlTlsTranslator}: Connector/J 8.x vs 5.1.x property sets per mode, PEM
 * {@code data:} URL construction, pre-built keystore override, blank-skipping, and the
 * {@link MySqlTlsTranslator#hasExplicitTlsIntent(Map)} guard that protects TLS from the
 * legacy {@code useSSL=false} retry fallback.
 */
class MySqlTlsTranslatorTest {

    private static final String PEM_CA = "-----BEGIN CERTIFICATE-----\nMIIB-CA\n-----END CERTIFICATE-----\n";
    private static final String PEM_CLIENT_CERT = "-----BEGIN CERTIFICATE-----\nMIIB-CLIENT\n-----END CERTIFICATE-----\n";
    private static final String PEM_CLIENT_KEY = "-----BEGIN PRIVATE KEY-----\nMIIE-KEY\n-----END PRIVATE KEY-----\n";

    private static DriverConfig connectorJ8() {
        DriverConfig cfg = new DriverConfig();
        cfg.setJdbcDriverClass("com.mysql.cj.jdbc.Driver");
        cfg.setJdbcDriver("mysql-connector-java-8.0.33.jar");
        return cfg;
    }

    private static DriverConfig connectorJ5() {
        DriverConfig cfg = new DriverConfig();
        cfg.setJdbcDriverClass("com.mysql.jdbc.Driver");
        cfg.setJdbcDriver("mysql-connector-java-5.1.49.jar");
        return cfg;
    }

    private static SSLInfo ssl(MySqlTlsMode mode) {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode(mode.name());
        return ssl;
    }

    // ---- apply(): null / disabled are no-ops --------------------------------------------

    @Test
    void nullSslIsNoOp() {
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(null, connectorJ8(), p);
        assertTrue(p.isEmpty());
    }

    @Test
    void nullPropertiesIsNoOp() {
        // Must not throw.
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.REQUIRED), connectorJ8(), null);
    }

    @Test
    void disabledModeIsNoOp() {
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.DISABLED), connectorJ8(), p);
        assertTrue(p.isEmpty());
    }

    @Test
    void blankModeTreatedAsDisabled() {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode("   ");
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        assertTrue(p.isEmpty());
    }

    @Test
    void unrecognizedModeTreatedAsDisabled() {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode("PREFERRED");
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        assertTrue(p.isEmpty());
    }

    // ---- Connector/J 8.x ----------------------------------------------------------------

    @Test
    void v8RequiredSetsSslModeOnly() {
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.REQUIRED), connectorJ8(), p);
        assertEquals("REQUIRED", p.get("sslMode"));
        assertFalse(p.containsKey("useSSL"));
        assertFalse(p.containsKey("verifyServerCertificate"));
    }

    @Test
    void v8VerifyIdentityEmitsMode() {
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.VERIFY_IDENTITY), connectorJ8(), p);
        assertEquals("VERIFY_IDENTITY", p.get("sslMode"));
    }

    @Test
    void v8CaPemBecomesPemTrustStore() {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setCaPem(PEM_CA);
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        assertEquals("PEM", p.get("trustCertificateKeyStoreType"));
        String url = (String) p.get("trustCertificateKeyStoreUrl");
        assertTrue(url.startsWith("data:application/x-pem-file;base64,"), url);
        assertFalse(p.containsKey("clientCertificateKeyStoreUrl"));
    }

    @Test
    void v8MutualTlsEmitsClientStore() {
        SSLInfo ssl = ssl(MySqlTlsMode.REQUIRED);
        ssl.setClientCertPem(PEM_CLIENT_CERT);
        ssl.setClientPrivateKeyPem(PEM_CLIENT_KEY);
        ssl.setClientKeyPassword("keypass");
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        assertEquals("PEM", p.get("clientCertificateKeyStoreType"));
        String url = (String) p.get("clientCertificateKeyStoreUrl");
        assertTrue(url.startsWith("data:application/x-pem-file;base64,"), url);
        // cert + key are concatenated before encoding.
        assertTrue(url.length() > pemDataUrl(PEM_CLIENT_CERT).length());
        assertEquals("keypass", p.get("clientCertificateKeyStorePassword"));
    }

    @Test
    void v8MutualTlsWithoutPrivateKeyOmitsClientStore() {
        SSLInfo ssl = ssl(MySqlTlsMode.REQUIRED);
        ssl.setClientCertPem(PEM_CLIENT_CERT);
        // private key intentionally blank -> cannot build a client keystore
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        assertFalse(p.containsKey("clientCertificateKeyStoreUrl"));
    }

    // ---- pre-built keystore overrides PEM ----------------------------------------------

    @Test
    void keystoreOverrideReplacesPemStores() {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setCaPem(PEM_CA);
        ssl.setClientCertPem(PEM_CLIENT_CERT);
        ssl.setClientPrivateKeyPem(PEM_CLIENT_KEY);
        ssl.setKeyStoreType("PKCS12");
        ssl.setKeyStoreBytes("AAABAA=="); // already base64 keystore content
        ssl.setKeyStorePassword("storepass");
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        // The same keystore backs both trust and client stores.
        assertEquals("PKCS12", p.get("trustCertificateKeyStoreType"));
        assertEquals("PKCS12", p.get("clientCertificateKeyStoreType"));
        assertEquals("data:application/octet-stream;base64,AAABAA==",
                p.get("trustCertificateKeyStoreUrl"));
        assertEquals("data:application/octet-stream;base64,AAABAA==",
                p.get("clientCertificateKeyStoreUrl"));
        assertEquals("storepass", p.get("trustCertificateKeyStorePassword"));
        assertEquals("storepass", p.get("clientCertificateKeyStorePassword"));
        // PEM material is not emitted when a keystore overrides it.
        assertEquals("PKCS12", p.get("clientCertificateKeyStoreType"));
    }

    @Test
    void keystoreDefaultsToPkcs12WhenTypeBlank() {
        SSLInfo ssl = ssl(MySqlTlsMode.REQUIRED);
        ssl.setKeyStoreBytes("AAABAA==");
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        assertEquals("PKCS12", p.get("trustCertificateKeyStoreType"));
    }

    @Test
    void blankKeystorePasswordIsOmitted() {
        SSLInfo ssl = ssl(MySqlTlsMode.REQUIRED);
        ssl.setKeyStoreBytes("AAABAA==");
        // password blank
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        assertFalse(p.containsKey("trustCertificateKeyStorePassword"));
    }

    // ---- Connector/J 5.1.x --------------------------------------------------------------

    @Test
    void v5RequiredUsesLegacyFlagsWithoutVerification() {
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.REQUIRED), connectorJ5(), p);
        assertEquals("true", p.get("useSSL"));
        assertEquals("true", p.get("requireSSL"));
        assertEquals("false", p.get("verifyServerCertificate"));
        assertFalse(p.containsKey("sslMode"));
    }

    @Test
    void v5VerifyCaEnablesServerCertVerification() {
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.VERIFY_CA), connectorJ5(), p);
        assertEquals("true", p.get("verifyServerCertificate"));
    }

    @Test
    void v5VerifyIdentityAlsoVerifies() {
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.VERIFY_IDENTITY), connectorJ5(), p);
        assertEquals("true", p.get("verifyServerCertificate"));
    }

    @Test
    void v5Jar5_1StringDetectedAsLegacy() {
        DriverConfig cfg = new DriverConfig();
        cfg.setJdbcDriverClass("org.gjt.mm.mysql.Driver");
        cfg.setJdbcDriver("mysql-connector-java-5.1.49.jar");
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.REQUIRED), cfg, p);
        assertEquals("true", p.get("useSSL"));
        assertFalse(p.containsKey("sslMode"));
    }

    // ---- version detection edge cases ---------------------------------------------------

    @Test
    void nullDriverConfigDefaultsToV8() {
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.REQUIRED), null, p);
        assertEquals("REQUIRED", p.get("sslMode"));
    }

    @Test
    void unknownDriverClassDefaultsToV8() {
        DriverConfig cfg = new DriverConfig();
        cfg.setJdbcDriverClass("com.example.Unknown");
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.REQUIRED), cfg, p);
        assertEquals("REQUIRED", p.get("sslMode"));
    }

    // ---- hasExplicitTlsIntent (the retry-fallback guard) --------------------------------

    @Test
    void intentFalseForEmptyOrNull() {
        assertFalse(MySqlTlsTranslator.hasExplicitTlsIntent(null));
        assertFalse(MySqlTlsTranslator.hasExplicitTlsIntent(new HashMap<>()));
    }

    @Test
    void intentTrueForUseSSLTrue() {
        Map<String, Object> p = new HashMap<>();
        p.put("useSSL", "true");
        assertTrue(MySqlTlsTranslator.hasExplicitTlsIntent(p));
    }

    @Test
    void intentFalseForSslModeDisabled() {
        Map<String, Object> p = new HashMap<>();
        p.put("sslMode", "DISABLED");
        assertFalse(MySqlTlsTranslator.hasExplicitTlsIntent(p));
    }

    @Test
    void intentTrueForNonDisabledSslMode() {
        Map<String, Object> p = new HashMap<>();
        p.put("sslMode", "REQUIRED");
        assertTrue(MySqlTlsTranslator.hasExplicitTlsIntent(p));
    }

    @Test
    void intentTrueForTrustCertificatePrefix() {
        Map<String, Object> p = new HashMap<>();
        p.put("trustCertificateKeyStoreUrl", "data:...");
        assertTrue(MySqlTlsTranslator.hasExplicitTlsIntent(p));
    }

    @Test
    void intentTrueForClientCertificatePrefix() {
        Map<String, Object> p = new HashMap<>();
        p.put("clientCertificateKeyStoreType", "PEM");
        assertTrue(MySqlTlsTranslator.hasExplicitTlsIntent(p));
    }

    @Test
    void intentInspectsPropertiesAsWellAsMap() {
        // The connection-retry path carries Properties, not Map<String,Object>.
        Properties info = new Properties();
        info.put("sslMode", "VERIFY_CA");
        assertTrue(MySqlTlsTranslator.hasExplicitTlsIntent(info));

        Properties plain = new Properties();
        plain.put("user", "root");
        assertFalse(MySqlTlsTranslator.hasExplicitTlsIntent(plain));
    }

    @Test
    void intentFalseForUnrelatedProperties() {
        Properties info = new Properties();
        info.put("user", "root");
        info.put("password", "secret");
        info.put("connectTimeout", "10000");
        assertFalse(MySqlTlsTranslator.hasExplicitTlsIntent(info));
    }

    // ---- helpers ------------------------------------------------------------------------

    private static String pemDataUrl(String pem) {
        return "data:application/x-pem-file;base64," + java.util.Base64.getEncoder()
                .encodeToString(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
