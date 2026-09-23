package ai.chat2db.plugin.redis;

import ai.chat2db.plugin.redis.config.RedisScanConfig;
import ai.chat2db.plugin.redis.constant.RedisCommandTemplates;
import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.plugin.redis.enums.type.RedisDataType;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.model.RedisKeyScanResult;
import ai.chat2db.plugin.redis.type.RedisScanStoppedReason;
import ai.chat2db.plugin.redis.util.RedisScanUtils;
import ai.chat2db.plugin.redis.util.RedisValueUtils;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.ElapsedTimer;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RedisKeyScanner {

    private static final RedisKeyScanner INSTANCE = new RedisKeyScanner();

    private RedisKeyScanner() {
    }

    public static RedisKeyScanner getInstance() {
        return INSTANCE;
    }

    public RedisKeyScanResult scanKeys(Connection connection, String searchKey, String cursor, Integer count) {
        try (ElapsedTimer timer = ElapsedTimer.start()) {
            String currentCursor = RedisScanUtils.normalizeCursor(cursor);
            String matchPattern = RedisScanUtils.buildContainsMatchPattern(searchKey);
            int scanCount = RedisScanUtils.normalizeCount(count, RedisScanConfig.DEFAULT.defaultCount(),
                    RedisScanConfig.DEFAULT.minCount(), RedisScanConfig.DEFAULT.maxCount());
            List<RedisKey> redisKeys = new ArrayList<>();
            Set<String> names = new HashSet<>();
            int scanCalls = 0;
            RedisScanStoppedReason stoppedReason = RedisScanStoppedReason.CLIENT_LIMIT_REACHED;

            while (scanCalls < RedisScanConfig.DEFAULT.maxScanCallsPerRequest()
                    && redisKeys.size() < RedisScanConfig.DEFAULT.keyTargetPerRequest()) {
                currentCursor = scanOnce(connection, currentCursor, matchPattern, scanCount, redisKeys, names);
                scanCalls++;
                if (RedisConstants.SCAN_INITIAL_CURSOR.equals(currentCursor)) {
                    stoppedReason = RedisScanStoppedReason.REDIS_CURSOR_COMPLETE;
                    break;
                }
            }

            if (stoppedReason != RedisScanStoppedReason.REDIS_CURSOR_COMPLETE
                    && scanCalls >= RedisScanConfig.DEFAULT.maxScanCallsPerRequest()) {
                stoppedReason = RedisScanStoppedReason.COMMAND_BUDGET_REACHED;
            }
            boolean complete = RedisConstants.SCAN_INITIAL_CURSOR.equals(currentCursor);
            timer.close();
            return RedisKeyScanResult.builder()
                    .keys(redisKeys)
                    .nextCursor(currentCursor)
                    .hasMore(!complete)
                    .complete(complete)
                    .stoppedReason(stoppedReason.name())
                    .scanCalls(scanCalls)
                    .keysReturned(redisKeys.size())
                    .elapsedMs(timer.elapsedMs())
                    .build();
        }
    }

    private String scanOnce(Connection connection, String cursor, String matchPattern, int count, List<RedisKey> redisKeys,
            Set<String> names) {
        String query = String.format(RedisCommandTemplates.SCAN_MATCH_COUNT, cursor,
                RedisValueUtils.getRedisValue(matchPattern), count);
        return DefaultSQLExecutor.getInstance().execute(connection, query, resultSet -> {
            String nextCursor = RedisConstants.SCAN_INITIAL_CURSOR;
            while (resultSet.next()) {
                Object cursorValue = resultSet.getObject(1);
                if (cursorValue != null) {
                    nextCursor = cursorValue.toString();
                }
                List<?> keys = RedisScanUtils.getKeys(resultSet.getObject(2));
                for (Object object : keys) {
                    String keyName = object.toString();
                    if (!names.add(keyName)) {
                        continue;
                    }
                    redisKeys.add(buildKeyHandle(keyName));
                }
            }
            return RedisScanUtils.normalizeCursor(nextCursor);
        });
    }

    private RedisKey buildKeyHandle(String keyName) {
        RedisKey redisKey = new RedisKey();
        redisKey.setName(keyName);
        redisKey.setType(RedisDataType.normalizeCode(RedisScriptExecutor.getInstance().getKeyType(keyName)));
        String ttl = RedisScriptExecutor.getInstance().getTtl(keyName);
        if (StringUtils.isNotBlank(ttl)) {
            redisKey.setTtl(Long.parseLong(ttl));
        }
        return redisKey;
    }
}
