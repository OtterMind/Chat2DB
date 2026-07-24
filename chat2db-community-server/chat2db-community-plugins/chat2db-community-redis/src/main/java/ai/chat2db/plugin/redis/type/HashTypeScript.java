package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.RedisScriptExecutor;
import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.plugin.redis.model.Action;
import ai.chat2db.plugin.redis.model.HashValue;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.spi.DefaultSQLExecutor;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;

public class HashTypeScript extends BaseTypeScript implements ITypeScript {
    @Override
    public String getKey(RedisKey redisKey) {
        StringBuilder script = new StringBuilder();
        if (CollectionUtils.isEmpty(redisKey.getHashValues())) {
            script.append(RedisConstants.COMMAND_HASH_GET_ALL_PREFIX).append(getRedisValue(redisKey.getName()))
                    .append(RedisConstants.COMMAND_LINE_SEPARATOR);
        } else {
            script.append(RedisConstants.COMMAND_HASH_GET_PREFIX).append(getRedisValue(redisKey.getName()))
                    .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR)
                    .append(getRedisValue(redisKey.getHashValues().get(0).getField()))
                    .append(RedisConstants.COMMAND_LINE_SEPARATOR);
        }
        return script.toString();
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
            List<HashValue> hashValues = new ArrayList<>();
            while (resultSet.next()) {
                Object field = resultSet.getObject(RedisConstants.FIELD_FIELD);
                Object value = resultSet.getObject(RedisConstants.FIELD_VALUE);
                HashValue hs = new HashValue();
                if (Objects.nonNull(field)) {
                    hs.setField(field.toString());
                    hs.setValue(value != null ? value.toString() : null);
                }
                hashValues.add(hs);
            }
            rs.setHashValues(hashValues);
        });
        String ttl = RedisScriptExecutor.getInstance().getTtl(redisKey.getName());
        if (StringUtils.isNotBlank(ttl)) {
            rs.setTtl(Long.parseLong(ttl));
        } else {
            rs.setTtl(-1L);
        }
        rs.setValue(rs.getHashValues().toString());
        return rs;
    }

    @Override
    public List<String> createKey(RedisKey redisKey) {
        return addItem(redisKey);
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
            String delete = delete(oldKey.getName());
            return Lists.newArrayList(delete);
        }
        // The editor drops removed rows from the payload instead of flagging them,
        // so reconcile against the full old field list.
        Map<String, String> desired = fieldValues(newKey.getHashValues(), true);
        if (desired.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> existing = fieldValues(oldKey.getHashValues(), false);
        List<String> scripts = new ArrayList<>();
        List<String> removed = existing.keySet().stream().filter(field -> !desired.containsKey(field)).toList();
        if (!removed.isEmpty()) {
            StringBuilder script = new StringBuilder();
            script.append(RedisConstants.COMMAND_HASH_DELETE_PREFIX).append(getRedisValue(newKey.getName()))
                    .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
            for (String field : removed) {
                script.append(getRedisValue(field)).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
            }
            scripts.add(script.toString());
        }
        StringBuilder upserts = new StringBuilder();
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            if (!existing.containsKey(entry.getKey()) || !Objects.equals(existing.get(entry.getKey()), entry.getValue())) {
                upserts.append(getRedisValue(entry.getKey())).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR)
                        .append(getRedisValue(entry.getValue())).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
            }
        }
        if (upserts.length() > 0) {
            scripts.add(RedisConstants.COMMAND_HASH_SET_PREFIX + getRedisValue(newKey.getName())
                    + RedisConstants.COMMAND_ARGUMENT_SEPARATOR + upserts);
        }
        return scripts;
    }

    private Map<String, String> fieldValues(List<HashValue> values, boolean skipDeleted) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(values)) {
            return fields;
        }
        for (HashValue value : values) {
            if (value.getField() == null) {
                continue;
            }
            if (skipDeleted && ai.chat2db.plugin.redis.constant.ActionConstants.DELETE.equals(value.getAction())) {
                continue;
            }
            fields.put(value.getField(), StringUtils.defaultString(value.getValue()));
        }
        return fields;
    }

    private List<String> addItem(RedisKey redisKey) {
        if(redisKey == null || CollectionUtils.isEmpty(redisKey.getHashValues())) {
            return Lists.newArrayList();
        }
        List<String> scripts = new ArrayList<>();
        StringBuilder script = new StringBuilder();
        script.append(RedisConstants.COMMAND_HASH_SET_PREFIX).append(getRedisValue(redisKey.getName()))
                .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
        if (CollectionUtils.isNotEmpty(redisKey.getHashValues())) {
            for (HashValue field : redisKey.getHashValues()) {
                script.append(getRedisValue(field.getField())).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR)
                        .append(getRedisValue(field.getValue())).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
            }
        }
        scripts.add(script.toString());
        script = new StringBuilder();
        if (redisKey.getTtl() != null && redisKey.getTtl() > 0) {
            script.append(RedisConstants.COMMAND_EXPIRE_KEY_PREFIX).append(getRedisValue(redisKey.getName()))
                    .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR).append(redisKey.getTtl())
                    .append(RedisConstants.COMMAND_LINE_SEPARATOR);
            scripts.add(script.toString());
        }
        return scripts;
    }

}
