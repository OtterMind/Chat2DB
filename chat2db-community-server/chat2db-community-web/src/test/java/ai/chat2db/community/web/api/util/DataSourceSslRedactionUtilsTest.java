package ai.chat2db.community.web.api.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceSslRedactionUtilsTest {

    @Test
    void redactsTlsSecretFieldsRecursivelyWithoutDroppingBody() {
        String body = """
                {
                  "path": "/api/connection/datasource/create",
                  "ssl": {
                    "tlsMode": "VERIFY_CA",
                    "caPem": "public-ca",
                    "clientCertPem": "public-cert",
                    "clientPrivateKeyPem": "private-key",
                    "clientKeyPassword": "key-pass",
                    "trustStoreBytes": "trust-store",
                    "trustStorePassword": "trust-pass",
                    "keyStoreBytes": "key-store",
                    "keyStorePassword": "key-pass"
                  },
                  "items": [
                    {"ssl": {"clientPrivateKeyPem": "nested-private-key"}}
                  ]
                }
                """;

        String redacted = DataSourceSslRedactionUtils.redactJsonBody(body);

        assertFalse(redacted.contains("private-key"));
        assertFalse(redacted.contains("key-pass"));
        assertFalse(redacted.contains("trust-store"));
        assertFalse(redacted.contains("trust-pass"));
        assertFalse(redacted.contains("key-store"));
        assertFalse(redacted.contains("nested-private-key"));
        assertTrue(redacted.contains("public-ca"));
        assertTrue(redacted.contains("public-cert"));
        assertTrue(redacted.contains(DataSourceSslRedactionUtils.REDACTED));
    }

    @Test
    void preservesNullAndClearSecretSemanticsInLogs() {
        String body = """
                {"ssl":{"clientPrivateKeyPem":null,"clientKeyPassword":"","tlsMode":"VERIFY_CA"}}
                """;

        String redacted = DataSourceSslRedactionUtils.redactJsonBody(body);
        JSONObject ssl = JSON.parseObject(redacted).getJSONObject("ssl");

        assertTrue(ssl.containsKey("clientPrivateKeyPem"));
        assertEquals(null, ssl.get("clientPrivateKeyPem"));
        assertEquals("", ssl.getString("clientKeyPassword"));
        assertEquals("VERIFY_CA", ssl.getString("tlsMode"));
    }

    @Test
    void redactsNonJsonBodiesCompletely() {
        assertEquals(DataSourceSslRedactionUtils.REDACTED_BODY,
                DataSourceSslRedactionUtils.redactJsonBody("not-json clientPrivateKeyPem=secret"));
    }
}
