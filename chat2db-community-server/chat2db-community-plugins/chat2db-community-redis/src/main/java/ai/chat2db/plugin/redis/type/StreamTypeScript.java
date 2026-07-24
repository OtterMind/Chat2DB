package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.RedisScriptExecutor;
import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.plugin.redis.model.*;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.spi.DefaultSQLExecutor;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;

public class StreamTypeScript extends BaseTypeScript implements ITypeScript {
    @Override
    public String getKey(RedisKey redisKey) {
        return RedisConstants.COMMAND_STREAM_RANGE_PREFIX + getRedisValue(redisKey.getName())
                + RedisConstants.COMMAND_STREAM_RANGE_SUFFIX;
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
            List<StreamValue> streamValues = new ArrayList<>();
            while (resultSet.next()) {
                Object id = resultSet.getObject(RedisConstants.FIELD_ID);
                Object fields = resultSet.getObject(RedisConstants.FIELD_FIELDS);
                if (Objects.nonNull(id) && Objects.nonNull(fields)) {
                    if (fields instanceof Map) {
                        StreamValue streamValue = new StreamValue();
                        streamValue.setId(id.toString());
                        streamValue.setValues(new ArrayList<>());
                        Map<String, String> map = (Map<String, String>) fields;
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            KeyValue keyValue = new KeyValue();
                            keyValue.setKey(entry.getKey());
                            keyValue.setValue(entry.getValue());
                            streamValue.getValues().add(keyValue);
                        }
                        streamValues.add(streamValue);
                    }
                }
            }
            rs.setStreamValues(streamValues);
        });
        String ttl = RedisScriptExecutor.getInstance().getTtl(redisKey.getName());
        if (StringUtils.isNotBlank(ttl)) {
            rs.setTtl(Long.parseLong(ttl));
        } else {
            rs.setTtl(-1L);
        }
        rs.setValue(rs.getStreamValues().toString());
        return rs;
    }


    @Override
    public List<String> createKey(RedisKey redisKey) {
        if (redisKey == null || CollectionUtils.isEmpty(redisKey.getStreamValues())) {
            return Lists.newArrayList();
        }
        List<String> scripts = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(redisKey.getStreamValues())) {
            for (StreamValue value : redisKey.getStreamValues()) {
                if (CollectionUtils.isNotEmpty(value.getValues())) {
                    String s = createItem(redisKey.getName(), value);
                    scripts.add(s);
                }
            }
        }
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
            String del = delete(oldKey.getName());
            return List.of(del);
        } else {
            List<String> script = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(newKey.getStreamValues())) {
                for (StreamValue field : newKey.getStreamValues()) {
                    if (ai.chat2db.plugin.redis.constant.ActionConstants.DELETE.equals(field.getAction())) {
                        String s = deleteItem(newKey.getName(), field);
                        script.add(s);
                    }
                    if (ai.chat2db.plugin.redis.constant.ActionConstants.CREATE.equals(field.getAction())) {
                        String s = createItem(newKey.getName(), field);
                        script.add(s);
                    }
                    if (ai.chat2db.plugin.redis.constant.ActionConstants.UPDATE.equals(field.getAction())) {
                        String s = deleteItem(newKey.getName(), field);
                        script.add(s);
                        s = createItem(newKey.getName(), field);
                        script.add(s);
                    }
                }
            }
            return script;
        }
    }

    private String createItem(String name, StreamValue field) {
        StringBuilder script = new StringBuilder();
        script.append(RedisConstants.COMMAND_STREAM_ADD_PREFIX).append(getRedisValue(name))
                .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
        String id = StringUtils.isBlank(field.getId()) ? "*" : field.getId();
        script.append(id).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
        for (KeyValue entry : field.getValues()) {
            if (StringUtils.isNotBlank(entry.getKey()) && StringUtils.isNotBlank(entry.getValue())) {
                script.append(getRedisValue(entry.getKey())).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR)
                        .append(getRedisValue(entry.getValue())).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
            }
        }
        script.append(RedisConstants.COMMAND_LINE_SEPARATOR);
        return script.toString();
    }

    public String deleteItem(String name, StreamValue streamValue) {
        return RedisConstants.COMMAND_STREAM_DELETE_PREFIX + getRedisValue(name)
                + RedisConstants.COMMAND_ARGUMENT_SEPARATOR + streamValue.getId()
                + RedisConstants.COMMAND_LINE_SEPARATOR;
    }

}
