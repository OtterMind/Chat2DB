package ai.chat2db.plugin.bigquery;

import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BigQueryDBManagerTest {

    @Test
    void prepareExtendInfoCopiesImmutableInputAndReplacesManagedKeys() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setProject("new-project");
        connectInfo.setEmail("service@example.com");
        connectInfo.setKeyfile("/tmp/key.json");
        connectInfo.setExtendInfo(List.of(
                keyValue("ProjectId", "old-project"),
                keyValue("custom", "preserved")));

        List<KeyValue> result = BigQueryDBManager.prepareExtendInfo(connectInfo);

        assertEquals(5, result.size());
        assertEquals(1, count(result, "ProjectId"));
        assertEquals(1, count(result, "OAuthServiceAcctEmail"));
        assertEquals(1, count(result, "OAuthType"));
        assertEquals(1, count(result, "OAuthPvtKeyPath"));
        assertEquals("preserved", value(result, "custom"));
        assertEquals("new-project", value(result, "ProjectId"));
    }

    private static long count(List<KeyValue> values, String key) {
        return values.stream().filter(value -> key.equals(value.getKey())).count();
    }

    private static String value(List<KeyValue> values, String key) {
        return values.stream()
                .filter(value -> key.equals(value.getKey()))
                .findFirst()
                .map(KeyValue::getValue)
                .orElse(null);
    }

    private static KeyValue keyValue(String key, String value) {
        KeyValue keyValue = new KeyValue();
        keyValue.setKey(key);
        keyValue.setValue(value);
        return keyValue;
    }
}
