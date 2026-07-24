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
import java.util.Set;

import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;

public class SetTypeScript extends BaseTypeScript implements ITypeScript {
    @Override
    public String getKey(RedisKey redisKey) {
        return RedisConstants.COMMAND_SET_MEMBERS_PREFIX + getRedisValue(redisKey.getName());
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
            List<SetValue> zSetValues = new ArrayList<>();
            while (resultSet.next()) {
                Object value = resultSet.getObject(RedisConstants.FIELD_VALUE);
                SetValue setValue = new SetValue();
                if (Objects.nonNull(value)) {
                    setValue.setValue(value.toString());
                }
                zSetValues.add(setValue);
            }
            rs.setValues(zSetValues);
        });
        String ttl = RedisScriptExecutor.getInstance().getTtl(redisKey.getName());
        if (StringUtils.isNotBlank(ttl)) {
            rs.setTtl(Long.parseLong(ttl));
        } else {
            rs.setTtl(-1L);
        }
        rs.setValue(rs.getValues().toString());
        return rs;
    }

    @Override
    public List<String> createKey(RedisKey redisKey) {
        if(redisKey == null || CollectionUtils.isEmpty(redisKey.getValues())) {
            return Lists.newArrayList();
        }
        List<String> scripts = new ArrayList<>();
        StringBuilder script = new StringBuilder();
        script.append(RedisConstants.COMMAND_SET_ADD_PREFIX).append(getRedisValue(redisKey.getName()))
                .append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
        List<SetValue> valueList = redisKey.getValues();
        if(CollectionUtils.isNotEmpty(valueList)) {
            for (SetValue value : valueList) {
                script.append(getRedisValue(value.getValue())).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
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
        // Member edits carry only the new value, so reconcile against the full
        // old member list instead of trusting per-row action flags.
        Set<String> desired = memberSet(newKey.getValues(), true);
        if (desired.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> existing = memberSet(oldKey.getValues(), false);
        List<String> scripts = new ArrayList<>();
        List<String> removed = existing.stream().filter(value -> !desired.contains(value)).toList();
        if (!removed.isEmpty()) {
            scripts.add(membersCommand(RedisConstants.COMMAND_SET_REMOVE_PREFIX, newKey.getName(), removed));
        }
        List<String> added = desired.stream().filter(value -> !existing.contains(value)).toList();
        if (!added.isEmpty()) {
            scripts.add(membersCommand(RedisConstants.COMMAND_SET_ADD_PREFIX, newKey.getName(), added));
        }
        return scripts;
    }

    private Set<String> memberSet(List<SetValue> values, boolean skipDeleted) {
        Set<String> members = new java.util.LinkedHashSet<>();
        if (CollectionUtils.isEmpty(values)) {
            return members;
        }
        for (SetValue value : values) {
            if (skipDeleted && ai.chat2db.plugin.redis.constant.ActionConstants.DELETE.equals(value.getAction())) {
                continue;
            }
            members.add(StringUtils.defaultString(value.getValue()));
        }
        return members;
    }

    private String membersCommand(String commandPrefix, String name, List<String> members) {
        StringBuilder script = new StringBuilder();
        script.append(commandPrefix).append(getRedisValue(name)).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
        for (String member : members) {
            script.append(getRedisValue(member)).append(RedisConstants.COMMAND_ARGUMENT_SEPARATOR);
        }
        return script.toString();
    }

}
