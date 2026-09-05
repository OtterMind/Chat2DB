package ai.chat2db.plugin.mysql.account;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlAccountPreviewTokenTest {

    private final MysqlAccountPreviewToken signer = new MysqlAccountPreviewToken(new byte[32]);
    private final Instant issuedAt = Instant.parse("2026-09-05T00:00:00Z");

    @Test
    void tokenBindsSqlDatasourceAndExpiry() {
        String sql = "ALTER USER 'alice'@'%' REQUIRE SSL";
        String token = signer.issue(sql, 42L, issuedAt);

        assertTrue(signer.verify(token, sql, 42L, issuedAt.plusSeconds(299)));
        assertFalse(signer.verify(token, sql + " REQUIRE NONE", 42L, issuedAt.plusSeconds(1)));
        assertFalse(signer.verify(token, sql, 43L, issuedAt.plusSeconds(1)));
        assertFalse(signer.verify(token, sql, 42L, issuedAt.plusSeconds(301)));
    }

    @Test
    void rejectsMalformedAndLegacyDigestTokens() {
        String sql = "ALTER USER 'alice'@'%' REQUIRE SSL";

        assertFalse(signer.verify(null, sql, 42L, issuedAt));
        assertFalse(signer.verify("not-a-token", sql, 42L, issuedAt));
        assertFalse(signer.verify("1736035200000.invalid-base64!", sql, 42L, issuedAt));
        assertFalse(signer.verify("9c56cc51b374c3ba189210d5b6d4bf57790d351c96c47c0213ddafdd749d1a73",
                sql, 42L, issuedAt));
    }
}
