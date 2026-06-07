package ai.chat2db.spi.redis;

import java.util.List;
import java.util.function.Consumer;

public interface RedisKeyBrowser {

    RedisKeyScanResult streamKeys(String databaseName, String searchKey, String cursor, int count,
                                  Consumer<List<RedisKeyInfo>> batchConsumer);

    RedisKeyInfo queryKey(String databaseName, String keyName);

    void createKey(String databaseName, String keyName, String keyType, Object value, Long ttl);

    void updateKey(String databaseName, String originalKey, String updateKey, String keyType, Object value, Long ttl);

    void deleteKey(String databaseName, String keyName);
}
