package ai.chat2db.community.web.api.config.core;

import ai.chat2db.community.web.api.util.DataSourceSslRedactionUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.zalando.logbook.BodyFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebLogConfigurationTest {

    @Test
    void bodyFilterRedactsTlsSecretsBeforeLogbookWritesRequestBodies() {
        BodyFilter bodyFilter = new WebLogConfiguration().bodyFilter();
        String body = """
                {"ssl":{"clientPrivateKeyPem":"private-key","clientKeyPassword":"","trustStorePassword":null,"caPem":"ca"}}
                """;

        String redacted = bodyFilter.filter("application/json", body);
        JSONObject ssl = JSON.parseObject(redacted).getJSONObject("ssl");

        assertFalse(redacted.contains("private-key"));
        assertEquals(DataSourceSslRedactionUtils.REDACTED, ssl.getString("clientPrivateKeyPem"));
        assertEquals("", ssl.getString("clientKeyPassword"));
        assertEquals(null, ssl.get("trustStorePassword"));
        assertEquals("ca", ssl.getString("caPem"));
    }
}
