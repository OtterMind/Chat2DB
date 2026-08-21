package ai.chat2db.plugin.redis;

import ai.chat2db.community.domain.api.model.key.KeyDetailRequest;
import ai.chat2db.community.domain.api.model.key.KeyEntry;
import ai.chat2db.community.domain.api.model.key.KeyScanRequest;
import ai.chat2db.community.domain.api.model.key.KeyScanResult;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.model.RedisKeyScanResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisKeyOperationsTest {

    @Test
    void marksScanHandlesAsUnloadedAndDetailsAsLoaded() throws Exception {
        RedisKey redisKey = RedisKey.builder()
                .name("key")
                .type("string")
                .ttl(-1L)
                .value("value")
                .build();
        RedisMetaData redisMetaData = new RedisMetaData() {
            @Override
            public RedisKeyScanResult scanKeys(Connection connection, String searchKey, String cursor, Integer count) {
                return RedisKeyScanResult.builder().keys(List.of(redisKey)).build();
            }

            @Override
            public RedisKey keyDetail(Connection connection, String keyName) {
                return redisKey;
            }
        };
        RedisKeyOperations operations = new RedisKeyOperations(redisMetaData);

        KeyScanResult scanResult = operations.scan(null, new KeyScanRequest());
        KeyEntry detail = operations.keyDetail(null, new KeyDetailRequest());

        assertFalse(scanResult.getKeys().get(0).getDetailLoaded());
        assertTrue(detail.getDetailLoaded());
        ObjectMapper objectMapper = new ObjectMapper();
        assertTrue(objectMapper.writeValueAsString(scanResult).contains("\"detailLoaded\":false"));
        assertTrue(objectMapper.writeValueAsString(detail).contains("\"detailLoaded\":true"));
    }
}
