package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.datasource.MySqlTlsMode;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates a structured {@link SSLInfo} into MySQL Connector/J connection properties, merged
 * into the {@code extendInfo}-derived property map that already flows to the driver.
 *
 * <p>Properties (not URL query params) are used on purpose: they survive the SSH-tunnel URL
 * rewrite and keep version-specific parameters in one place. Connector/J 8.0.x uses
 * {@code sslMode} plus {@code trustCertificateKeyStoreUrl}/{@code clientCertificateKeyStoreUrl}
 * with temporary PKCS12/JKS file URLs; 5.1.x uses the legacy {@code useSSL}/{@code requireSSL}/
 * {@code verifyServerCertificate} family with the same supported store URLs.
 */
public final class MySqlTlsTranslator {

    private static final String KEY_STORE_TYPE_PKCS12 = "PKCS12";
    private static final String KEY_STORE_TYPE_JKS = "JKS";
    private static final char[] GENERATED_STORE_PASSWORD = new char[0];
    private static final String CERTIFICATE_LABEL = "CERTIFICATE";
    private static final String PRIVATE_KEY_LABEL = "PRIVATE KEY";
    private static final String ENCRYPTED_PRIVATE_KEY_LABEL = "ENCRYPTED PRIVATE KEY";
    private static final Set<Path> TEMPORARY_STORES = ConcurrentHashMap.newKeySet();
    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

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
        cleanupTemporaryStores(properties);
        if (ssl == null || MySqlTlsMode.fromString(ssl.getTlsMode()) == MySqlTlsMode.DISABLED) {
            removeTlsProperties(properties);
            return;
        }
        validateExclusiveSources(ssl);
        MySqlTlsMode mode = MySqlTlsMode.fromString(ssl.getTlsMode());
        try {
            if (isConnectorJ8(driverConfig)) {
                applyV8(ssl, mode, properties);
            } else {
                applyV5(ssl, mode, properties);
            }
        } catch (RuntimeException exception) {
            cleanupTemporaryStores(properties);
            throw exception;
        }
    }

    private static void validateExclusiveSources(SSLInfo ssl) {
        if (StringUtils.isNotBlank(ssl.getCaPem()) && StringUtils.isNotBlank(ssl.getTrustStoreBytes())) {
            throw new BusinessException("datasource.tls.conflictingTrustMaterial");
        }
        boolean hasClientPem = StringUtils.isNotBlank(ssl.getClientCertPem())
                || StringUtils.isNotBlank(ssl.getClientPrivateKeyPem());
        if (hasClientPem && StringUtils.isNotBlank(ssl.getKeyStoreBytes())) {
            throw new BusinessException("datasource.tls.conflictingClientMaterial");
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

    public static String diagnosticMessage(SQLException exception, Map<?, ?> properties) {
        String original = exception == null ? "" : StringUtils.defaultString(exception.getMessage());
        if (!hasExplicitTlsIntent(properties)) {
            return original;
        }
        String chain = exceptionChainText(exception).toLowerCase();
        String diagnostic = null;
        if (chain.contains("expired") || chain.contains("notafter") || chain.contains("not valid after")) {
            diagnostic = "TLS certificate is expired. Replace the server/client certificate or lower the TLS mode "
                    + "only after confirming the datasource policy.";
        } else if (chain.contains("no subject alternative names matching")
                || chain.contains("doesn't correspond to desired host")
                || chain.contains("hostname") && chain.contains("match")) {
            diagnostic = "TLS hostname verification failed. Connect with the hostname listed in the certificate SAN "
                    + "or use VERIFY_CA only when hostname verification is not required.";
        } else if (chain.contains("pkix path building failed")
                || chain.contains("unable to find valid certification path")
                || chain.contains("sun.security.validator.validatorexception")
                || chain.contains("certificate_unknown")) {
            diagnostic = "TLS certificate authority is not trusted. Upload the issuing CA PEM or a trust-store "
                    + "containing that CA.";
        } else if (chain.contains("badpaddingexception")
                || chain.contains("keystore was tampered with")
                || chain.contains("password was incorrect")
                || chain.contains("cannot recover key")
                || chain.contains("given final block not properly padded")) {
            diagnostic = "TLS private-key or key-store password is incorrect. Re-enter the client key password or "
                    + "client key-store password for this datasource.";
        }
        if (diagnostic == null) {
            return original;
        }
        return original.isBlank() ? diagnostic : original + ". " + diagnostic;
    }

    private static String exceptionChainText(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            builder.append(cursor.getClass().getName()).append(':');
            if (cursor.getMessage() != null) {
                builder.append(cursor.getMessage());
            }
            builder.append('\n');
            cursor = cursor.getCause();
        }
        return builder.toString();
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
        if (mode == MySqlTlsMode.VERIFY_IDENTITY) {
            throw new BusinessException("datasource.tls.verifyIdentityUnsupportedConnectorJ5");
        }
        p.put("useSSL", "true");
        p.put("requireSSL", "true");
        p.put("verifyServerCertificate", mode == MySqlTlsMode.VERIFY_CA ? "true" : "false");
        applyTrustStore(ssl, p);
        applyClientStore(ssl, p);
    }

    private static void applyTrustStore(SSLInfo ssl, Map<String, Object> p) {
        if (StringUtils.isNotBlank(ssl.getTrustStoreBytes())) {
            p.put("trustCertificateKeyStoreType", trustStoreType(ssl));
            p.put("trustCertificateKeyStoreUrl", writeSuppliedTrustStoreUrl(ssl));
            putIfNotBlank(p, "trustCertificateKeyStorePassword", ssl.getTrustStorePassword());
            return;
        }
        if (StringUtils.isNotBlank(ssl.getCaPem())) {
            p.put("trustCertificateKeyStoreType", KEY_STORE_TYPE_PKCS12);
            p.put("trustCertificateKeyStoreUrl", createTrustStoreUrl(ssl.getCaPem()));
            p.put("trustCertificateKeyStorePassword", "");
        }
    }

    private static void applyClientStore(SSLInfo ssl, Map<String, Object> p) {
        if (StringUtils.isNotBlank(ssl.getKeyStoreBytes())) {
            p.put("clientCertificateKeyStoreType", storeType(ssl));
            p.put("clientCertificateKeyStoreUrl", writeSuppliedKeyStoreUrl(ssl));
            putIfNotBlank(p, "clientCertificateKeyStorePassword", ssl.getKeyStorePassword());
            return;
        }
        if (StringUtils.isNotBlank(ssl.getClientCertPem()) && StringUtils.isNotBlank(ssl.getClientPrivateKeyPem())) {
            p.put("clientCertificateKeyStoreType", KEY_STORE_TYPE_PKCS12);
            p.put("clientCertificateKeyStoreUrl", createClientStoreUrl(ssl));
            p.put("clientCertificateKeyStorePassword", "");
        }
    }

    private static String trustStoreType(SSLInfo ssl) {
        return storeType(StringUtils.defaultIfBlank(ssl.getTrustStoreType(), KEY_STORE_TYPE_PKCS12));
    }

    private static String storeType(SSLInfo ssl) {
        return storeType(StringUtils.defaultIfBlank(ssl.getKeyStoreType(), KEY_STORE_TYPE_PKCS12));
    }

    private static String storeType(String requestedType) {
        String type = StringUtils.defaultIfBlank(requestedType, KEY_STORE_TYPE_PKCS12).toUpperCase();
        if (!KEY_STORE_TYPE_PKCS12.equals(type) && !KEY_STORE_TYPE_JKS.equals(type)) {
            throw new BusinessException("datasource.tls.unsupportedKeyStoreType", new Object[]{type});
        }
        return type;
    }

    private static void putIfNotBlank(Map<String, Object> p, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            p.put(key, value);
        }
    }

    private static String writeSuppliedTrustStoreUrl(SSLInfo ssl) {
        String type = trustStoreType(ssl);
        byte[] storeBytes = decodeStoreBytes(ssl.getTrustStoreBytes(), "trustStoreBytes");
        validateKeyStoreBytes(type, storeBytes, ssl.getTrustStorePassword(), "trustStoreBytes");
        return writeTempStoreBytes(storeBytes, type, "chat2db-mysql-trust-store-");
    }

    private static String writeSuppliedKeyStoreUrl(SSLInfo ssl) {
        String type = storeType(ssl);
        byte[] storeBytes = decodeStoreBytes(ssl.getKeyStoreBytes(), "keyStoreBytes");
        validateKeyStoreBytes(type, storeBytes, ssl.getKeyStorePassword(), "keyStoreBytes");
        return writeTempStoreBytes(storeBytes, type, "chat2db-mysql-client-store-");
    }

    private static byte[] decodeStoreBytes(String keyStoreBytes, String field) {
        byte[] storeBytes;
        try {
            storeBytes = Base64.getDecoder().decode(StringUtils.deleteWhitespace(keyStoreBytes));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("datasource.tls.invalidKeyStoreBase64", new Object[]{field}, e);
        }
        return storeBytes;
    }

    private static String createTrustStoreUrl(String caPem) {
        List<Certificate> certificates = parseCertificates(caPem, "caPem");
        try {
            KeyStore keyStore = KeyStore.getInstance(KEY_STORE_TYPE_PKCS12);
            keyStore.load(null, GENERATED_STORE_PASSWORD);
            for (int i = 0; i < certificates.size(); i++) {
                keyStore.setCertificateEntry("ca-" + i, certificates.get(i));
            }
            return writeGeneratedStoreUrl(keyStore, "chat2db-mysql-trust-store-");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("datasource.tls.invalidCaCertificate", null, e);
        }
    }

    private static String createClientStoreUrl(SSLInfo ssl) {
        List<Certificate> chain = parseCertificates(ssl.getClientCertPem(), "clientCertPem");
        PrivateKey privateKey = parsePrivateKey(ssl.getClientPrivateKeyPem(), ssl.getClientKeyPassword());
        try {
            KeyStore keyStore = KeyStore.getInstance(KEY_STORE_TYPE_PKCS12);
            keyStore.load(null, GENERATED_STORE_PASSWORD);
            keyStore.setKeyEntry("client", privateKey, GENERATED_STORE_PASSWORD, chain.toArray(Certificate[]::new));
            return writeGeneratedStoreUrl(keyStore, "chat2db-mysql-client-store-");
        } catch (Exception e) {
            throw new BusinessException("datasource.tls.invalidClientCertificate", null, e);
        }
    }

    private static List<Certificate> parseCertificates(String pem, String field) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<Certificate> certificates = new ArrayList<>();
            for (PemBlock block : pemBlocks(pem, CERTIFICATE_LABEL)) {
                byte[] der = Base64.getMimeDecoder().decode(block.body());
                certificates.add(factory.generateCertificate(new ByteArrayInputStream(der)));
            }
            if (certificates.isEmpty()) {
                throw new BusinessException("datasource.tls.missingCertificate", new Object[]{field});
            }
            return certificates;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("datasource.tls.invalidCertificate", new Object[]{field}, e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem, String password) {
        PemBlock block = firstPemBlock(pem, PRIVATE_KEY_LABEL, ENCRYPTED_PRIVATE_KEY_LABEL);
        if (block == null) {
            throw new BusinessException("datasource.tls.missingPrivateKey");
        }
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(block.body());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("datasource.tls.invalidPrivateKey", null, e);
        }
        if (ENCRYPTED_PRIVATE_KEY_LABEL.equals(block.label())) {
            if (StringUtils.isBlank(password)) {
                throw new BusinessException("datasource.tls.privateKeyPasswordRequired");
            }
            der = decryptEncryptedPrivateKey(der, password);
        } else if (!PRIVATE_KEY_LABEL.equals(block.label())) {
            throw new BusinessException("datasource.tls.privateKeyPkcs8Required");
        }
        return generatePrivateKey(der);
    }

    private static List<PemBlock> pemBlocks(String pem, String label) {
        List<PemBlock> blocks = new ArrayList<>();
        int index = 0;
        PemBlock block;
        while ((block = nextPemBlock(pem, label, index)) != null) {
            blocks.add(block);
            index = block.endIndex();
        }
        return blocks;
    }

    private static PemBlock firstPemBlock(String pem, String... labels) {
        if (pem == null) {
            return null;
        }
        PemBlock first = null;
        for (String label : labels) {
            PemBlock candidate = nextPemBlock(pem, label, 0);
            if (candidate != null && (first == null || candidate.startIndex() < first.startIndex())) {
                first = candidate;
            }
        }
        return first;
    }

    private static PemBlock nextPemBlock(String pem, String label, int fromIndex) {
        if (pem == null) {
            return null;
        }
        String begin = pemBoundary("BEGIN", label);
        String end = pemBoundary("END", label);
        int beginIndex = pem.indexOf(begin, fromIndex);
        if (beginIndex < 0) {
            return null;
        }
        int bodyStart = beginIndex + begin.length();
        int endIndex = pem.indexOf(end, bodyStart);
        if (endIndex < 0) {
            return null;
        }
        String body = pem.substring(bodyStart, endIndex);
        return new PemBlock(label, body, beginIndex, endIndex + end.length());
    }

    private static String pemBoundary(String marker, String label) {
        return "-----" + marker + " " + label + "-----";
    }

    private static byte[] decryptEncryptedPrivateKey(byte[] encryptedKey, String password) {
        try {
            EncryptedPrivateKeyInfo encryptedInfo = new EncryptedPrivateKeyInfo(encryptedKey);
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(encryptedInfo.getAlgName());
            Cipher cipher = Cipher.getInstance(encryptedInfo.getAlgName());
            cipher.init(Cipher.DECRYPT_MODE, secretKeyFactory.generateSecret(new PBEKeySpec(password.toCharArray())),
                    encryptedInfo.getAlgParameters());
            return encryptedInfo.getKeySpec(cipher).getEncoded();
        } catch (Exception e) {
            throw new BusinessException("datasource.tls.invalidPrivateKeyPassword", null, e);
        }
    }

    private static PrivateKey generatePrivateKey(byte[] pkcs8) {
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8);
        for (String algorithm : List.of("RSA", "EC", "DSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (Exception ignored) {
                // Try the next common key algorithm.
            }
        }
        throw new BusinessException("datasource.tls.invalidPrivateKey");
    }

    private static void validateKeyStoreBytes(String type, byte[] storeBytes, String password, String field) {
        try {
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(new ByteArrayInputStream(storeBytes), passwordChars(password));
        } catch (Exception e) {
            throw new BusinessException("datasource.tls.invalidKeyStore", new Object[]{field, type}, e);
        }
    }

    private static String writeGeneratedStoreUrl(KeyStore keyStore, String prefix) {
        Path file = null;
        try {
            file = createOwnerOnlyTempFile(prefix, ".p12");
            try (OutputStream outputStream = Files.newOutputStream(file)) {
                keyStore.store(outputStream, GENERATED_STORE_PASSWORD);
            }
            TEMPORARY_STORES.add(file);
            return file.toUri().toURL().toString();
        } catch (Exception e) {
            deleteCreatedFile(file);
            throw new BusinessException("datasource.tls.storeWriteFailed", null, e);
        }
    }

    private static String writeTempStoreBytes(byte[] storeBytes, String type, String prefix) {
        Path file = null;
        try {
            file = createOwnerOnlyTempFile(prefix, KEY_STORE_TYPE_JKS.equals(type) ? ".jks" : ".p12");
            Files.write(file, storeBytes);
            TEMPORARY_STORES.add(file);
            return file.toUri().toURL().toString();
        } catch (MalformedURLException e) {
            deleteCreatedFile(file);
            throw new BusinessException("datasource.tls.storeUrlFailed", null, e);
        } catch (Exception e) {
            deleteCreatedFile(file);
            throw new BusinessException("datasource.tls.storeWriteFailed", null, e);
        }
    }

    private static Path createOwnerOnlyTempFile(String prefix, String suffix) throws Exception {
        Path file;
        if (Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).supportsFileAttributeView("posix")) {
            file = Files.createTempFile(prefix, suffix,
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS));
            Files.setPosixFilePermissions(file, OWNER_ONLY_PERMISSIONS);
            return file;
        }
        file = Files.createTempFile(prefix, suffix);
        boolean secured = file.toFile().setReadable(false, false)
                && file.toFile().setWritable(false, false)
                && file.toFile().setExecutable(false, false)
                && file.toFile().setReadable(true, true)
                && file.toFile().setWritable(true, true);
        if (!secured) {
            Files.deleteIfExists(file);
            throw new IllegalStateException("Cannot secure TLS temporary store permissions");
        }
        return file;
    }

    static void cleanupTemporaryStores(Map<?, ?> properties) {
        if (properties == null) {
            return;
        }
        deleteTemporaryStore(storePath(properties.get("trustCertificateKeyStoreUrl")));
        deleteTemporaryStore(storePath(properties.get("clientCertificateKeyStoreUrl")));
    }

    private static Path storePath(Object value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value.toString());
            return "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri).toAbsolutePath().normalize() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void deleteTemporaryStore(Path file) {
        if (file == null) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (!TEMPORARY_STORES.remove(normalized)) {
            return;
        }
        try {
            Files.deleteIfExists(normalized);
        } catch (Exception ignored) {
            normalized.toFile().deleteOnExit();
        }
    }

    private static void deleteCreatedFile(Path file) {
        if (file == null) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        TEMPORARY_STORES.remove(normalized);
        try {
            Files.deleteIfExists(normalized);
        } catch (Exception ignored) {
            normalized.toFile().deleteOnExit();
        }
    }

    private static char[] passwordChars(String password) {
        return password == null ? null : password.toCharArray();
    }

    private record PemBlock(String label, String body, int startIndex, int endIndex) {
    }
}
