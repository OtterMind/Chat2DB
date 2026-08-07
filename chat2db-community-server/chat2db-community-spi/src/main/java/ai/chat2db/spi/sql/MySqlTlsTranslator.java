package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.datasource.MySqlTlsMode;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Translates a structured {@link SSLInfo} into MySQL Connector/J connection properties, merged
 * into the {@code extendInfo}-derived property map that already flows to the driver.
 *
 * <p>Properties (not URL query params) are used on purpose: they survive the SSH-tunnel URL
 * rewrite and keep version-specific parameters in one place. Connector/J 8.0.x uses
 * {@code sslMode} plus {@code trustCertificateKeyStoreUrl}/{@code clientCertificateKeyStoreUrl}
 * with inline PEM {@code data:} URLs; 5.1.x uses the legacy {@code useSSL}/{@code requireSSL}/
 * {@code verifyServerCertificate} family with the same PEM store URLs.
 */
public final class MySqlTlsTranslator {

    private static final String PEM_DATA_URL_PREFIX = "data:application/x-pem-file;base64,";

    private MySqlTlsTranslator() {
    }

    /**
     * Merge TLS connection properties for {@code ssl} into {@code properties}. When
     * {@code ssl} is null or the mode is {@link MySqlTlsMode#DISABLED}, previously merged
     * TLS properties are removed so deleting or disabling the TLS config actually takes
     * effect on the next connection instead of leaving stale sslMode/useSSL behind.
     *
     * @param ssl          the structured TLS config (sensitive fields already decrypted)
     * @param driverConfig the resolved driver config, used for Connector/J version detection
     * @param properties   the property map to merge into; never null
     */
    public static void apply(SSLInfo ssl, DriverConfig driverConfig, Map<String, Object> properties) {
        if (properties == null) {
            return;
        }
        if (ssl == null || MySqlTlsMode.fromString(ssl.getTlsMode()) == MySqlTlsMode.DISABLED) {
            removeTlsProperties(properties);
            return;
        }
        MySqlTlsMode mode = MySqlTlsMode.fromString(ssl.getTlsMode());
        if (isConnectorJ8(driverConfig)) {
            applyV8(ssl, mode, properties);
        } else {
            applyV5(ssl, mode, properties);
        }
    }

    private static void removeTlsProperties(Map<String, Object> p) {
        p.remove("sslMode");
        p.remove("useSSL");
        p.remove("requireSSL");
        p.remove("verifyServerCertificate");
        p.remove("trustCertificateKeyStoreType");
        p.remove("trustCertificateKeyStoreUrl");
        p.remove("trustCertificateKeyStorePassword");
        p.remove("clientCertificateKeyStoreType");
        p.remove("clientCertificateKeyStoreUrl");
        p.remove("clientCertificateKeyStorePassword");
    }

    /**
     * Whether the resolved properties express explicit TLS intent, so the legacy
     * {@code useSSL=false} retry fallback must not clobber them.
     */
    /**
     * Whether the resolved properties express explicit TLS intent, so the legacy
     * {@code useSSL=false} retry fallback must not clobber them.
     *
     * <p>Accepts a raw {@code Map<?, ?>} so it can inspect either the
     * {@code Map<String, Object>} built for the driver or the {@code Properties} carried
     * through the connection-retry path.
     */
    public static boolean hasExplicitTlsIntent(Map<?, ?> properties) {
        if (properties == null || properties.isEmpty()) {
            return false;
        }
        if (properties.containsKey("useSSL") || properties.containsKey("requireSSL")
                || properties.containsKey("verifyServerCertificate")) {
            return true;
        }
        Object sslMode = properties.get("sslMode");
        if (sslMode != null && !"DISABLED".equalsIgnoreCase(sslMode.toString())) {
            return true;
        }
        for (Object key : properties.keySet()) {
            if (key == null) {
                continue;
            }
            String s = key.toString();
            if (s.startsWith("trustCertificate") || s.startsWith("clientCertificate")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectorJ8(DriverConfig driverConfig) {
        if (driverConfig == null) {
            return true;
        }
        String driverClass = driverConfig.getJdbcDriverClass();
        if (driverClass != null) {
            if (driverClass.contains(".cj.")) {
                return true;
            }
            if ("com.mysql.jdbc.Driver".equals(driverClass)) {
                return false;
            }
        }
        String jar = driverConfig.getJdbcDriver();
        if (jar != null && jar.contains("5.1")) {
            return false;
        }
        return true;
    }

    private static void applyV8(SSLInfo ssl, MySqlTlsMode mode, Map<String, Object> p) {
        p.put("sslMode", mode.name());
        applyTrustStore(ssl, p);
        applyClientStore(ssl, p);
    }

    private static void applyV5(SSLInfo ssl, MySqlTlsMode mode, Map<String, Object> p) {
        p.put("useSSL", "true");
        p.put("requireSSL", "true");
        p.put("verifyServerCertificate",
                (mode == MySqlTlsMode.VERIFY_CA || mode == MySqlTlsMode.VERIFY_IDENTITY) ? "true" : "false");
        applyTrustStore(ssl, p);
        applyClientStore(ssl, p);
    }

    private static void applyTrustStore(SSLInfo ssl, Map<String, Object> p) {
        // The trust store only carries CA material; keyStoreBytes is the client identity
        // keystore (mutual TLS) and must not double as the trust store.
        if (StringUtils.isNotBlank(ssl.getCaPem())) {
            p.put("trustCertificateKeyStoreType", "PEM");
            p.put("trustCertificateKeyStoreUrl", pemDataUrl(ssl.getCaPem()));
        }
    }

    private static void applyClientStore(SSLInfo ssl, Map<String, Object> p) {
        if (StringUtils.isNotBlank(ssl.getKeyStoreBytes())) {
            p.put("clientCertificateKeyStoreType", storeType(ssl));
            p.put("clientCertificateKeyStoreUrl", binaryDataUrl(ssl.getKeyStoreBytes()));
            putIfNotBlank(p, "clientCertificateKeyStorePassword", ssl.getKeyStorePassword());
            return;
        }
        if (StringUtils.isNotBlank(ssl.getClientCertPem()) && StringUtils.isNotBlank(ssl.getClientPrivateKeyPem())) {
            // Connector/J accepts a combined cert+key PEM for the client keystore.
            p.put("clientCertificateKeyStoreType", "PEM");
            p.put("clientCertificateKeyStoreUrl",
                    pemDataUrl(ssl.getClientCertPem() + "\n" + ssl.getClientPrivateKeyPem()));
            putIfNotBlank(p, "clientCertificateKeyStorePassword", ssl.getClientKeyPassword());
        }
    }

    private static String storeType(SSLInfo ssl) {
        return StringUtils.isNotBlank(ssl.getKeyStoreType()) ? ssl.getKeyStoreType() : "PKCS12";
    }

    private static void putIfNotBlank(Map<String, Object> p, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            p.put(key, value);
        }
    }

    private static String pemDataUrl(String pem) {
        return PEM_DATA_URL_PREFIX + Base64.getEncoder()
                .encodeToString(pem.getBytes(StandardCharsets.UTF_8));
    }

    private static String binaryDataUrl(String base64Bytes) {
        // keyStoreBytes is already Base64-encoded keystore content; wrap as a binary data URL.
        return "data:application/octet-stream;base64," + base64Bytes;
    }
}
