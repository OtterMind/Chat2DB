package ai.chat2db.plugin.mysql.account;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class MysqlAccountPreviewToken {

    static final MysqlAccountPreviewToken INSTANCE = new MysqlAccountPreviewToken(randomKey());

    private static final String ALGORITHM = "HmacSHA256";
    private static final Duration VALIDITY = Duration.ofMinutes(5);

    private final SecretKeySpec key;

    MysqlAccountPreviewToken(byte[] key) {
        this.key = new SecretKeySpec(key.clone(), ALGORITHM);
    }

    String issue(String sql, Long dataSourceId) {
        return issue(sql, dataSourceId, Instant.now());
    }

    boolean verify(String token, String sql, Long dataSourceId) {
        return verify(token, sql, dataSourceId, Instant.now());
    }

    String issue(String sql, Long dataSourceId, Instant now) {
        long expiresAt = now.plus(VALIDITY).toEpochMilli();
        byte[] signature = sign(payload(expiresAt, dataSourceId, sql));
        return expiresAt + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    boolean verify(String token, String sql, Long dataSourceId, Instant now) {
        if (token == null) {
            return false;
        }
        int separator = token.indexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return false;
        }
        try {
            long expiresAt = Long.parseLong(token.substring(0, separator));
            if (expiresAt < now.toEpochMilli()) {
                return false;
            }
            byte[] supplied = Base64.getUrlDecoder().decode(token.substring(separator + 1));
            byte[] expected = sign(payload(expiresAt, dataSourceId, sql));
            return MessageDigest.isEqual(expected, supplied);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign MySQL account preview token", e);
        }
    }

    private String payload(long expiresAt, Long dataSourceId, String sql) {
        return expiresAt + "\0" + (dataSourceId == null ? "" : dataSourceId) + "\0" + sql;
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
