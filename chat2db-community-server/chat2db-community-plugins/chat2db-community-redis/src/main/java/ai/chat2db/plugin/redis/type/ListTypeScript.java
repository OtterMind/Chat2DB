package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.RedisScriptExecutor;
import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.plugin.redis.model.*;
import ai.chat2db.spi.DefaultSQLExecutor;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;

public class ListTypeScript extends BaseTypeScript implements ITypeScript {
    @Override
    public String getKey(RedisKey redisKey) {
        return RedisConstants.COMMAND_LIST_RANGE_PREFIX + getRedisValue(redisKey.getName())
                + RedisConstants.COMMAND_LIST_RANGE_ALL_SUFFIX;
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
            List<ListValue> listValues = new ArrayList<>();
            while (resultSet.next()) {
                Object value = resultSet.getObject(RedisConstants.FIELD_VALUE);
                ListValue setValue = new ListValue();
                if (Objects.nonNull(value)) {
                    setValue.setValue(value.toString());
                }
                listValues.add(setValue);
            }
            rs.setListValues(listValues);
        });
        String ttl = RedisScriptExecutor.getInstance().getTtl(redisKey.getName());
        if (StringUtils.isNotBlank(ttl)) {
            rs.setTtl(Long.parseLong(ttl));
        } else {
            rs.setTtl(-1L);
        }
        rs.setValue(rs.getListValues().toString());
        return rs;
    }

    @Override
    public List<String> createKey(RedisKey redisKey) {
        if(redisKey == null || CollectionUtils.isEmpty(redisKey.getListValues())) {
            return Lists.newArrayList();
        }
        List<ListValue> desired = desiredValues(redisKey);
        if (desired.isEmpty()) {
            return Lists.newArrayList();
        }
        List<String> scripts = new ArrayList<>();
        scripts.add(pushAll(redisKey.getName(), desired));
        if (redisKey.getTtl() != null && redisKey.getTtl() > 0) {
            StringBuilder script = new StringBuilder();
            script.append(RedisConstants.COMMAND_EXPIRE_KEY_PREFIX).append(getRedisValue(redisKey.getName()))
                    .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR).append(redisKey.getTtl())
                    .append(RedisConstants.COMMAND_LINE_SEPARATOR);
            scripts.add(script.toString());
        }
        return scripts;
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
            return List.of(delete);
        }
        // The editor submits the full desired list (rows flagged delete excluded),
        // so rewrite the key to match it. Element edits carry only the new value,
        // which makes value-based LREM/LINSERT reconciliation unsafe.
        List<ListValue> desired = desiredValues(newKey);
        if (desired.isEmpty()) {
            return Lists.newArrayList();
        }
        List<String> scripts = new ArrayList<>();
        scripts.add(delete(newKey.getName()));
        scripts.add(pushAll(newKey.getName(), desired));
        return scripts;
    }

    private List<ListValue> desiredValues(RedisKey redisKey) {
        if (CollectionUtils.isEmpty(redisKey.getListValues())) {
            return Lists.newArrayList();
        }
        return redisKey.getListValues().stream()
                .filter(value -> !ai.chat2db.plugin.redis.constant.ActionConstants.DELETE.equals(value.getAction()))
                .collect(java.util.stream.Collectors.toList());
    }

    private String pushAll(String name, List<ListValue> values) {
        StringBuilder script = new StringBuilder();
        script.append(RedisConstants.COMMAND_LIST_RIGHT_PUSH_PREFIX).append(getRedisValue(name))
                .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
        for (ListValue value : values) {
            script.append(getRedisValue(StringUtils.defaultString(value.getValue())))
                    .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
        }
        return script.toString();
    }

}
