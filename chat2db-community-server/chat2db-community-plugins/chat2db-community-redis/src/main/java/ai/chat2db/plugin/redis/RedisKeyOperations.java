package ai.chat2db.plugin.redis;

import ai.chat2db.community.domain.api.model.key.KeyCreate;
import ai.chat2db.community.domain.api.model.key.KeyDelete;
import ai.chat2db.community.domain.api.model.key.KeyDetailRequest;
import ai.chat2db.community.domain.api.model.key.KeyEntry;
import ai.chat2db.community.domain.api.model.key.KeyRequest;
import ai.chat2db.community.domain.api.model.key.KeyScanRequest;
import ai.chat2db.community.domain.api.model.key.KeyScanResult;
import ai.chat2db.community.domain.api.model.key.KeyUpdate;
import ai.chat2db.plugin.redis.converter.RedisKeyConverter;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.model.RedisKeyScanResult;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IKeyOperations;
import ai.chat2db.spi.sql.Chat2DBContext;
import java.sql.Connection;
import java.util.List;
import java.util.stream.Collectors;

public class RedisKeyOperations implements IKeyOperations {

    private final RedisMetaData redisMetaData;
    private final RedisKeyConverter redisKeyConverter = new RedisKeyConverter();

    public RedisKeyOperations(RedisMetaData redisMetaData) {
        this.redisMetaData = redisMetaData;
    }

    @Override
    public List<KeyEntry> query(Connection connection, KeyRequest query) {
        return redisMetaData.keys(connection, query.getDatabaseName(), null, query.getPattern())
                .stream()
                .map(redisKeyConverter::redisKey2keyEntry)
                .collect(Collectors.toList());
    }

    @Override
    public KeyScanResult scan(Connection connection, KeyScanRequest query) {
        RedisKeyScanResult redisKeyScanResult = redisMetaData.scanKeys(connection, query.getPattern(),
                query.getCursor(), query.getCount());
        KeyScanResult result = new KeyScanResult();
        result.setKeys(redisKeyScanResult.getKeys()
                .stream()
                .map(redisKeyConverter::redisKey2keyEntry)
                .collect(Collectors.toList()));
        result.setNextCursor(redisKeyScanResult.getNextCursor());
        result.setHasMore(redisKeyScanResult.getHasMore());
        result.setComplete(redisKeyScanResult.getComplete());
        result.setStoppedReason(redisKeyScanResult.getStoppedReason());
        result.setScanCalls(redisKeyScanResult.getScanCalls());
        result.setKeysReturned(redisKeyScanResult.getKeysReturned());
        result.setElapsedMs(redisKeyScanResult.getElapsedMs());
        return result;
    }

    @Override
    public KeyEntry keyDetail(Connection connection, KeyDetailRequest query) {
        return redisKeyConverter.redisKey2keyEntry(redisMetaData.keyDetail(query.getKeyName()));
    }

    @Override
    public KeyEntry create(Connection connection, KeyCreate command) {
        RedisScriptExecutor.getInstance().createRedisKey(redisKeyConverter.keyCreate2redisKey(command));
        return redisKeyConverter.redisKey2keyEntry(redisMetaData.keyDetail(command.getName()));
    }

    @Override
    public KeyEntry update(Connection connection, KeyUpdate command) {
        RedisKey oldKey = command.getOldKey() == null ? null : redisKeyConverter.keyEntry2redisKey(command.getOldKey());
        RedisKey newKey = command.getNewKey() == null ? null : redisKeyConverter.keyEntry2redisKey(command.getNewKey());
        RedisScriptExecutor.getInstance().update(oldKey, newKey);
        String resultName = newKey != null ? newKey.getName() : oldKey != null ? oldKey.getName() : null;
        if (resultName == null) {
            return null;
        }
        return redisKeyConverter.redisKey2keyEntry(redisMetaData.keyDetail(resultName));
    }

    @Override
    public void delete(Connection connection, KeyDelete command) {
        String dropTableSql = Chat2DBContext.getDbManager()
                .dropTable(connection, command.getDatabaseName(), null, command.getKeyName());
        DefaultSQLExecutor.getInstance().execute(connection, dropTableSql, resultSet -> null);
    }
}
