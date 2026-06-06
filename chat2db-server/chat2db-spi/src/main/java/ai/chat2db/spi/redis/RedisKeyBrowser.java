package ai.chat2db.spi.redis;

import java.util.List;

public interface RedisKeyBrowser {

    List<RedisKeyInfo> listKeys(String databaseName, String searchKey, int count);

    RedisKeyInfo queryKey(String databaseName, String keyName);
}
