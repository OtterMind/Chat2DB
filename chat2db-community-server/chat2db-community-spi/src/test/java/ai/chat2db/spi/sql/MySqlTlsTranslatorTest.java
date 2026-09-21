package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.datasource.MySqlTlsMode;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link MySqlTlsTranslator}: Connector/J 8.x vs 5.1.x property sets per mode,
 * driver-readable PKCS12/JKS file URL construction, pre-built keystore override, blank-skipping, and the
 * {@link MySqlTlsTranslator#hasExplicitTlsIntent(Map)} guard that protects TLS from the
 * legacy {@code useSSL=false} retry fallback.
 */
class MySqlTlsTranslatorTest {

    private static final String PEM_CA = """
            -----BEGIN CERTIFICATE-----
            MIIDCTCCAfGgAwIBAgIUQvG2FafS9Aeb3j6aaNY2RSE6ZpEwDQYJKoZIhvcNAQEL
            BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDgyMzExMzMwNVoXDTI2MDgy
            NDExMzMwNVowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
            AAOCAQ8AMIIBCgKCAQEA1Um3UHGVXhYeLLb+tktI4w1Dc1DfKoCDxfpB7uKK1AAf
            In43TPHg7ml//RR6eJk8ZAdHnf8jmXd8HedNroC+AKG22Po/F7Yeo20GpsXHq/2A
            rpfo9Dg8ZsOObpE20IRko/NgLwxr+XFzoWMKaOhIRxX/BQeh/MGwtR5A0l4SUU5E
            n9cbwEC7u9cQWGU4OrN/fuo8oYOlerNYIwetaGoYPsQ5xjGiKxvvCGPzbLoCN/+i
            qQY4mY9TDmXRr0OcByzxXgCRQhVKK+nCDthE1OWLT28uZRb4DnhK2TVwt+b3RU9E
            GdBd3SqlUdLoBNSkgZNsGdpjsVYohWYSbVZJdSMU2QIDAQABo1MwUTAdBgNVHQ4E
            FgQUpOJO/yt4e1/Bxahb3kEOunTw9WAwHwYDVR0jBBgwFoAUpOJO/yt4e1/Bxahb
            3kEOunTw9WAwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAJYtf
            /EK0qrciZMZvQypBSnA/YHLCnRtgnHy+aSsAtxtLOSp2MWJ0oOtSghIePNNISipv
            WaGgs+aM7QF87Qj5VOQKtE9rkpExa6lilzEli2/F6rsT9Icf6d6PnMz8RUQ5x6UJ
            i3gFlE8W+W7TxIcuVDdO4gL9/Qwt1W6N5MgHgT0Nbikf+OcYHnFnHMF3ynm7eiS/
            geemx1osmqj4ZsbJD4JySntpdy2kO64VSO3RH3bKfmVqtUSRRIGqooQIsV6mk0ML
            xbVk7AferuvX5Ysf/gkjt/pG/KRgGOLQfavb+eSCpYSCOOH76ixoMjyWN2jxBiV9
            5Tox458l5JNIDOMfAA==
            -----END CERTIFICATE-----
            """;
    private static final String PEM_CLIENT_CERT = PEM_CA;
    private static final String PEM_CLIENT_KEY = generatedPrivateKeyPem();

    private static DriverConfig connectorJ8() {
        DriverConfig cfg = new DriverConfig();
        cfg.setJdbcDriverClass("com.mysql.cj.jdbc.Driver");
        cfg.setJdbcDriver("mysql-connector-java-8.0.30.jar");
        return cfg;
    }

    private static DriverConfig connectorJ5() {
        DriverConfig cfg = new DriverConfig();
        cfg.setJdbcDriverClass("com.mysql.jdbc.Driver");
        cfg.setJdbcDriver("mysql-connector-java-5.1.47.jar");
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
    void unrecognizedModeFailsLoudly() {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode("PREFERRED");
        Map<String, Object> p = new HashMap<>();
        assertThrows(BusinessException.class, () -> MySqlTlsTranslator.apply(ssl, connectorJ8(), p));
    }

    @Test
    void caPemWithoutCertificateBlockReportsFieldSpecificDiagnostic() {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setCaPem("not a certificate");
        Map<String, Object> p = new HashMap<>();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> MySqlTlsTranslator.apply(ssl, connectorJ8(), p));

        assertEquals("datasource.tls.missingCertificate", exception.getCode());
        assertEquals("caPem", exception.getArgs()[0]);
        assertFalse(p.containsKey("trustCertificateKeyStoreUrl"));
    }

    @Test
    void malformedCaPemReportsCertificateDiagnostic() {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setCaPem("""
                -----BEGIN CERTIFICATE-----
                not-base64
                -----END CERTIFICATE-----
                """);
        Map<String, Object> p = new HashMap<>();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> MySqlTlsTranslator.apply(ssl, connectorJ8(), p));

        assertEquals("datasource.tls.invalidCertificate", exception.getCode());
        assertEquals("caPem", exception.getArgs()[0]);
        assertFalse(p.containsKey("trustCertificateKeyStoreUrl"));
    }

    @Test
    void clientCertificateWithoutPrivateKeyReportsPrivateKeyDiagnostic() {
        SSLInfo ssl = ssl(MySqlTlsMode.REQUIRED);
        ssl.setClientCertPem(PEM_CLIENT_CERT);
        ssl.setClientPrivateKeyPem("not a private key");
        Map<String, Object> p = new HashMap<>();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> MySqlTlsTranslator.apply(ssl, connectorJ8(), p));

        assertEquals("datasource.tls.missingPrivateKey", exception.getCode());
        assertFalse(p.containsKey("clientCertificateKeyStoreUrl"));
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
        assertEquals("PKCS12", p.get("trustCertificateKeyStoreType"));
        String url = (String) p.get("trustCertificateKeyStoreUrl");
        assertTrue(url.startsWith("file:"), url);
        assertLoadableKeyStore(url, "PKCS12", "");
        assertEquals("", p.get("trustCertificateKeyStorePassword"));
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
        assertEquals("PKCS12", p.get("clientCertificateKeyStoreType"));
        String url = (String) p.get("clientCertificateKeyStoreUrl");
        assertTrue(url.startsWith("file:"), url);
        assertLoadableKeyStore(url, "PKCS12", "");
        assertEquals("", p.get("clientCertificateKeyStorePassword"));
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

    // ---- PEM and pre-built store sources are mutually exclusive ------------------------

    @Test
    void clientPemAndKeyStoreCannotBeSuppliedTogether() {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setCaPem(PEM_CA);
        ssl.setClientCertPem(PEM_CLIENT_CERT);
        ssl.setClientPrivateKeyPem(PEM_CLIENT_KEY);
        ssl.setKeyStoreType("PKCS12");
        ssl.setKeyStoreBytes(base64KeyStore("PKCS12", "storepass"));
        ssl.setKeyStorePassword("storepass");
        Map<String, Object> p = new HashMap<>();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> MySqlTlsTranslator.apply(ssl, connectorJ8(), p));

        assertEquals("datasource.tls.conflictingClientMaterial", exception.getCode());
        assertFalse(p.containsKey("trustCertificateKeyStoreUrl"));
        assertFalse(p.containsKey("clientCertificateKeyStoreUrl"));
    }

    @Test
    void caPemAndTrustStoreCannotBeSuppliedTogether() {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setCaPem(PEM_CA);
        ssl.setTrustStoreType("JKS");
        ssl.setTrustStoreBytes(base64KeyStore("JKS", "trustpass"));
        ssl.setTrustStorePassword("trustpass");
        Map<String, Object> p = new HashMap<>();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> MySqlTlsTranslator.apply(ssl, connectorJ8(), p));

        assertEquals("datasource.tls.conflictingTrustMaterial", exception.getCode());
        assertFalse(p.containsKey("trustCertificateKeyStoreUrl"));
    }

    @Test
    void suppliedTrustStoreIsIndependentFromClientKeyStore() {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setTrustStoreType("JKS");
        ssl.setTrustStoreBytes(base64KeyStore("JKS", "trustpass"));
        ssl.setTrustStorePassword("trustpass");
        ssl.setKeyStoreType("PKCS12");
        ssl.setKeyStoreBytes(base64KeyStore("PKCS12", "clientpass"));
        ssl.setKeyStorePassword("clientpass");
        Map<String, Object> p = new HashMap<>();

        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);

        assertEquals("JKS", p.get("trustCertificateKeyStoreType"));
        assertEquals("PKCS12", p.get("clientCertificateKeyStoreType"));
        assertLoadableKeyStore((String) p.get("trustCertificateKeyStoreUrl"), "JKS", "trustpass");
        assertLoadableKeyStore((String) p.get("clientCertificateKeyStoreUrl"), "PKCS12", "clientpass");
        assertEquals("trustpass", p.get("trustCertificateKeyStorePassword"));
        assertEquals("clientpass", p.get("clientCertificateKeyStorePassword"));
    }

    @Test
    void temporaryStoresUseOwnerOnlyPermissionsWhenPosixIsAvailable() throws Exception {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setTrustStoreBytes(base64KeyStore("PKCS12", null));
        Map<String, Object> p = new HashMap<>();

        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        Path path = Path.of(new URL((String) p.get("trustCertificateKeyStoreUrl")).toURI());
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                assertEquals(PosixFilePermissions.fromString("rw-------"), permissions);
            }
        } finally {
            MySqlTlsTranslator.cleanupTemporaryStores(p);
        }
    }

    @Test
    void translatorFailureRemovesAlreadyCreatedTemporaryStore() throws Exception {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setCaPem(PEM_CA);
        ssl.setKeyStoreBytes("not-base64%%%");
        Map<String, Object> p = new HashMap<>();

        assertThrows(BusinessException.class, () -> MySqlTlsTranslator.apply(ssl, connectorJ8(), p));

        Path trustStore = Path.of(new URL((String) p.get("trustCertificateKeyStoreUrl")).toURI());
        assertFalse(Files.exists(trustStore));
    }

    @Test
    void suppliedTrustStoreDefaultsToPkcs12WhenTypeBlank() {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setTrustStoreBytes(base64KeyStore("PKCS12", null));
        Map<String, Object> p = new HashMap<>();

        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);

        assertEquals("PKCS12", p.get("trustCertificateKeyStoreType"));
        assertLoadableKeyStore((String) p.get("trustCertificateKeyStoreUrl"), "PKCS12", null);
        assertFalse(p.containsKey("trustCertificateKeyStorePassword"));
    }

    @Test
    void invalidTrustStoreBase64ReportsFieldSpecificDiagnostic() {
        SSLInfo ssl = ssl(MySqlTlsMode.VERIFY_CA);
        ssl.setTrustStoreBytes("not-base64%%%");
        Map<String, Object> p = new HashMap<>();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> MySqlTlsTranslator.apply(ssl, connectorJ8(), p));

        assertEquals("datasource.tls.invalidKeyStoreBase64", exception.getCode());
        assertEquals("trustStoreBytes", exception.getArgs()[0]);
    }

    @Test
    void invalidClientKeyStorePasswordReportsFieldSpecificDiagnostic() {
        SSLInfo ssl = ssl(MySqlTlsMode.REQUIRED);
        ssl.setKeyStoreType("PKCS12");
        ssl.setKeyStoreBytes(base64KeyStore("PKCS12", "actualpass"));
        ssl.setKeyStorePassword("wrongpass");
        Map<String, Object> p = new HashMap<>();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> MySqlTlsTranslator.apply(ssl, connectorJ8(), p));

        assertEquals("datasource.tls.invalidKeyStore", exception.getCode());
        assertEquals("keyStoreBytes", exception.getArgs()[0]);
        assertEquals("PKCS12", exception.getArgs()[1]);
    }

    @Test
    void keystoreDefaultsToPkcs12WhenTypeBlank() {
        SSLInfo ssl = ssl(MySqlTlsMode.REQUIRED);
        ssl.setKeyStoreBytes(base64KeyStore("PKCS12", null));
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        assertEquals("PKCS12", p.get("clientCertificateKeyStoreType"));
        assertLoadableKeyStore((String) p.get("clientCertificateKeyStoreUrl"), "PKCS12", null);
    }

    @Test
    void blankKeystorePasswordIsOmitted() {
        SSLInfo ssl = ssl(MySqlTlsMode.REQUIRED);
        ssl.setKeyStoreBytes(base64KeyStore("PKCS12", null));
        // password blank
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, connectorJ8(), p);
        assertFalse(p.containsKey("clientCertificateKeyStorePassword"));
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
    void v5VerifyIdentityIsRejectedBecauseHostnameVerificationIsNotGuaranteed() {
        Map<String, Object> p = new HashMap<>();
        BusinessException exception = assertThrows(BusinessException.class,
                () -> MySqlTlsTranslator.apply(ssl(MySqlTlsMode.VERIFY_IDENTITY), connectorJ5(), p));

        assertEquals("datasource.tls.verifyIdentityUnsupportedConnectorJ5", exception.getCode());
        assertTrue(p.isEmpty());
    }

    @Test
    void v5Jar5_1StringDetectedAsLegacy() {
        DriverConfig cfg = new DriverConfig();
        cfg.setJdbcDriverClass("org.gjt.mm.mysql.Driver");
        cfg.setJdbcDriver("mysql-connector-java-5.1.47.jar");
        Map<String, Object> p = new HashMap<>();
        MySqlTlsTranslator.apply(ssl(MySqlTlsMode.REQUIRED), cfg, p);
        assertEquals("true", p.get("useSSL"));
        assertFalse(p.containsKey("sslMode"));
    }

    @Test
    void connectorJMatrixArtifactMatchesGeneratedModeProperties() throws Exception {
        try (InputStream inputStream = MySqlTlsTranslatorTest.class
                .getResourceAsStream("/mysql-tls/connector-j-matrix.csv")) {
            assertTrue(inputStream != null, "matrix fixture must exist");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                assertEquals("connector,jdbcDriverClass,jdbcDriver,tlsMode,expectedModeProperty,"
                        + "expectedModeValue,expectedVerifyServerCertificate,expectedErrorCode", header);
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] columns = line.split(",", -1);
                    assertEquals(8, columns.length, () -> Arrays.toString(columns));
                    DriverConfig cfg = new DriverConfig();
                    cfg.setJdbcDriverClass(columns[1]);
                    cfg.setJdbcDriver(columns[2]);
                    Map<String, Object> p = new HashMap<>();

                    if (!columns[7].isBlank()) {
                        BusinessException exception = assertThrows(BusinessException.class,
                                () -> MySqlTlsTranslator.apply(ssl(MySqlTlsMode.valueOf(columns[3])), cfg, p), line);
                        assertEquals(columns[7], exception.getCode(), line);
                        assertTrue(p.isEmpty(), line);
                        continue;
                    }

                    MySqlTlsTranslator.apply(ssl(MySqlTlsMode.valueOf(columns[3])), cfg, p);
                    assertEquals(columns[5], p.get(columns[4]), line);
                    if (!columns[6].isBlank()) {
                        assertEquals(columns[6], p.get("verifyServerCertificate"), line);
                    } else {
                        assertFalse(p.containsKey("verifyServerCertificate"), line);
                    }
                }
            }
        }
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
        p.put("trustCertificateKeyStoreUrl", "file:/tmp/store.p12");
        assertTrue(MySqlTlsTranslator.hasExplicitTlsIntent(p));
    }

    @Test
    void intentTrueForClientCertificatePrefix() {
        Map<String, Object> p = new HashMap<>();
        p.put("clientCertificateKeyStoreType", "PKCS12");
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

    // ---- connection diagnostics --------------------------------------------------------

    @Test
    void diagnosticsAddExpiredCertificateGuidanceForTlsConnections() {
        String message = MySqlTlsTranslator.diagnosticMessage(
                sqlException("javax.net.ssl.SSLHandshakeException: NotAfter: Sat Aug 24 11:33:05 PDT 2026"),
                tlsProperties());

        assertTrue(message.contains("TLS certificate is expired"), message);
    }

    @Test
    void diagnosticsAddUntrustedCaGuidanceForTlsConnections() {
        String message = MySqlTlsTranslator.diagnosticMessage(
                sqlException("PKIX path building failed: unable to find valid certification path to requested target"),
                tlsProperties());

        assertTrue(message.contains("Upload the issuing CA PEM"), message);
    }

    @Test
    void diagnosticsAddHostnameMismatchGuidanceForTlsConnections() {
        String message = MySqlTlsTranslator.diagnosticMessage(
                sqlException("No subject alternative names matching IP address 127.0.0.1 found"),
                tlsProperties());

        assertTrue(message.contains("TLS hostname verification failed"), message);
    }

    @Test
    void diagnosticsAddWrongPasswordGuidanceForTlsConnections() {
        String message = MySqlTlsTranslator.diagnosticMessage(
                sqlException("java.io.IOException: keystore was tampered with, or password was incorrect"),
                tlsProperties());

        assertTrue(message.contains("key-store password is incorrect"), message);
    }

    @Test
    void diagnosticsDoNotRewritePlaintextConnections() {
        SQLException exception = sqlException("PKIX path building failed");
        assertEquals(exception.getMessage(), MySqlTlsTranslator.diagnosticMessage(exception, new HashMap<>()));
    }

    // ---- helpers ------------------------------------------------------------------------

    private static String base64KeyStore(String type, String password) {
        try {
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(null, password == null ? null : password.toCharArray());
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            keyStore.store(outputStream, password == null ? null : password.toCharArray());
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String generatedPrivateKeyPem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
            return pemBoundary("BEGIN") + base64 + "\n" + pemBoundary("END");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String pemBoundary(String marker) {
        return "-----" + marker + " PRIVATE KEY-----\n";
    }

    private static void assertLoadableKeyStore(String url, String type, String password) {
        try (InputStream inputStream = new URL(url).openStream()) {
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(inputStream, password == null ? null : password.toCharArray());
            assertTrue(keyStore.size() >= 0);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, Object> tlsProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("sslMode", "VERIFY_IDENTITY");
        return properties;
    }

    private static SQLException sqlException(String message) {
        return new SQLException("Cannot create connection", new SQLException(message));
    }
}
