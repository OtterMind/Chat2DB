package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.RedisScriptExecutor;
import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.plugin.redis.enums.type.RedisDataType;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;

public class JsonTypeScript extends BaseTypeScript implements ITypeScript {

    @Override
    public String getKey(RedisKey redisKey) {
        return RedisConstants.COMMAND_JSON_GET_KEY_PREFIX + getRedisValue(redisKey.getName());
    }

    @Override
    public RedisKey getKeyR(Connection connection, RedisKey redisKey) {
        if (!existKey(connection, redisKey.getName())) {
            return null;
        }
        RedisKey result = new RedisKey();
        result.setName(redisKey.getName());
        result.setType(RedisDataType.JSON.getCode());
        DefaultSQLExecutor.getInstance().execute(connection, getKey(redisKey), resultSet -> {
            while (resultSet.next()) {
                String value = resultSet.getString(RedisConstants.FIELD_VALUE);
                if (Objects.nonNull(value)) {
                    result.setValue(value);
                }
            }
        });
        String ttl = RedisScriptExecutor.getInstance().getTtl(connection, redisKey.getName());
        result.setTtl(StringUtils.isNotBlank(ttl) ? Long.parseLong(ttl) : -1L);
        return result;
    }

    @Override
    public List<String> createKey(RedisKey redisKey) {
        if (redisKey == null || redisKey.getValue() == null) {
            return List.of();
        }
        String script = jsonSet(redisKey.getName(), redisKey.getValue());
        if (redisKey.getTtl() != null && redisKey.getTtl() > 0) {
            return List.of(script, RedisConstants.COMMAND_EXPIRE_KEY_PREFIX + getRedisValue(redisKey.getName())
                    + RedisConstants.COMMAND_ARGUMENT_SEPARATOR + redisKey.getTtl());
        }
        return List.of(script);
    }

    @Override
    public List<String> updateKey(RedisKey oldKey, RedisKey newKey) {
        if (oldKey == null && newKey == null) {
            return null;
        }
        if (oldKey == null) {
            return createKey(newKey);
        }
        if (newKey == null) {
            return List.of(delete(oldKey.getName()));
        }
        if (Objects.equals(oldKey.getValue(), newKey.getValue())) {
            return null;
        }
        if (newKey.getValue() == null) {
            return null;
        }
        return List.of(jsonSet(newKey.getName(), newKey.getValue())
                + RedisConstants.COMMAND_SET_KEY_IF_EXISTS_SUFFIX);
    }

    private String jsonSet(String name, String value) {
        return RedisConstants.COMMAND_JSON_SET_KEY_PREFIX + getRedisValue(name)
                + RedisConstants.COMMAND_ARGUMENT_SEPARATOR + getRedisValue("$")
                + RedisConstants.COMMAND_ARGUMENT_SEPARATOR + getRedisValue(value);
    }
}
