package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.RedisScriptExecutor;
import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;


public class StringTypeScript extends BaseTypeScript implements ITypeScript {

    @Override
    public String getKey(RedisKey redisKey) {
        return RedisConstants.COMMAND_GET_KEY_PREFIX + getRedisValue(redisKey.getName());
    }

    @Override
    public RedisKey getKeyR(Connection connection, RedisKey redisKey) {
        if (!existKey(connection, redisKey.getName())) {
            return null;
        }
        String script = getKey(redisKey);
        RedisKey rs = new RedisKey();
        rs.setName(redisKey.getName());
        rs.setType(redisKey.getType());
        DefaultSQLExecutor.getInstance().execute(connection, script, resultSet -> {
            while (resultSet.next()) {
                Object value = resultSet.getObject(RedisConstants.FIELD_VALUE);
                if (Objects.nonNull(value)) {
                    rs.setValue(value.toString());
                }
            }
        });
        String ttl = RedisScriptExecutor.getInstance().getTtl(redisKey.getName());
        if (StringUtils.isNotBlank(ttl)) {
            rs.setTtl(Long.parseLong(ttl));
        } else {
            rs.setTtl(-1L);
        }
        return rs;
    }

    @Override
    public List<String> createKey(RedisKey redisKey) {
        if (redisKey == null || redisKey.getValue() == null) {
            return List.of();
        }
        StringBuilder script = new StringBuilder();
        script.append(RedisConstants.COMMAND_SET_KEY_PREFIX).append(getRedisValue(redisKey.getName()))
                .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR).append(getRedisValue(redisKey.getValue()));
        if (redisKey.getTtl() != null && redisKey.getTtl() > 0) {
            script.append(RedisConstants.COMMAND_EXPIRE_ARGUMENT_PREFIX).append(redisKey.getTtl());
        }
        return List.of(script.toString());
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
            String del = RedisConstants.COMMAND_DELETE_KEY_PREFIX + getRedisValue(oldKey.getName());
            return List.of(del);
        }
        if (Objects.equals(oldKey.getValue(), newKey.getValue())) {
            return null;
        }
        if (newKey.getValue() == null) {
            return null;
        }
        String s = RedisConstants.COMMAND_SET_KEY_PREFIX + getRedisValue(newKey.getName())
                + RedisConstants.COMMAND_ARGUMENT_SEPARATOR + getRedisValue(newKey.getValue())
                + RedisConstants.COMMAND_SET_KEY_IF_EXISTS_SUFFIX;
        return List.of(s);
    }
}
