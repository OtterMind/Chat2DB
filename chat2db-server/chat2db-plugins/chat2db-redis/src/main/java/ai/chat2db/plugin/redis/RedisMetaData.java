package ai.chat2db.plugin.redis;

import ai.chat2db.spi.CommandExecutor;
import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.jdbc.DefaultMetaService;
import ai.chat2db.spi.model.Database;
import ai.chat2db.spi.model.Schema;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.redis.RedisCommandMonitor;
import ai.chat2db.spi.redis.RedisKeyBrowser;
import ai.chat2db.spi.redis.RedisKeyInfo;
import ai.chat2db.spi.redis.RedisKeyScanResult;
import ai.chat2db.spi.ssh.SSHManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectInfo;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.google.common.collect.Lists;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.async.RedisAsyncCommands;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Redis 元数据和键浏览服务实现。
 *
 * 该类负责：
 * - 获取 Redis 数据库列表
 * - 浏览和查询 Redis 键信息
 * - 创建、更新、删除 Redis 键
 * - 监控 Redis 命令流
 */
@Slf4j
public class RedisMetaData extends DefaultMetaService implements MetaData, RedisKeyBrowser, RedisCommandMonitor {

    private static final int KEY_SCAN_BATCH_SIZE = 1000;
    private static final int VALUE_PREVIEW_LIMIT = 5;
    private static final int VALUE_TEXT_LIMIT = 200;
    private static final CommandExecutor COMMAND_EXECUTOR = new RedisCommandExecutor();

    @Override
    public List<Database> databases(Connection connection) {
        try (RedisConnectionProvider.RedisConnectionContext context =
                     RedisConnectionProvider.open(Chat2DBContext.getConnectInfo())) {
            RedisAsyncCommands<String, String> commands = context.connection().async();
            Map<String, String> config = commands.configGet("databases").toCompletableFuture().join();
            String count = config.get("databases");
            List<Database> databases = new ArrayList<>();
            if (StringUtils.isNotBlank(count)) {
                for (int i = 0; i < Integer.parseInt(count); i++) {
                    databases.add(Database.builder().name(String.valueOf(i)).build());
                }
            }
            return databases;
        }
    }

    @Override
    @SneakyThrows
    public List<Schema> schemas(Connection connection, String databaseName) {
        return List.of();
    }

    @Override
    @SneakyThrows
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        return List.of();
    }

    @Override
    public CommandExecutor getCommandExecutor() {
        // 提供 Redis 命令执行器，用于执行 Redis 相关 SQL 转换后的命令。
        return COMMAND_EXECUTOR;
    }


    @Override
    public RedisKeyScanResult streamKeys(String databaseName, String searchKey, String cursor, int count,
                                         Consumer<List<RedisKeyInfo>> batchConsumer) {
        // 流式扫描 Redis 键并按批次返回，支持分页和模糊匹配。
        try (RedisConnectionProvider.RedisConnectionContext context =
                     RedisConnectionProvider.open(Chat2DBContext.getConnectInfo())) {
            RedisAsyncCommands<String, String> commands = context.connection().async();
            selectDatabase(commands, databaseName).join();
            String pattern = StringUtils.isBlank(searchKey) ? null : searchKey;
            if (isInitialCursor(cursor) && isExactKeyPattern(pattern)) {
                return queryExactKey(commands, pattern, batchConsumer);
            }
            // count < 0 means fetch all keys for the Redis data page.
            return scanKeyInfo(commands, pattern, cursor,
                    count < 0 ? Long.MAX_VALUE : count == 0 ? KEY_SCAN_BATCH_SIZE : count, batchConsumer);
        }
    }

    @Override
    public RedisKeyInfo queryKey(String databaseName, String keyName) {
        try (RedisConnectionProvider.RedisConnectionContext context =
                     RedisConnectionProvider.open(Chat2DBContext.getConnectInfo())) {
            RedisAsyncCommands<String, String> commands = context.connection().async();
            selectDatabase(commands, databaseName).join();
            return buildKeyInfo(commands, keyName, true).join();
        }
    }

    @Override
    public void createKey(String databaseName, String keyName, String keyType, Object value, Long ttl) {
        // 创建新的 Redis 键，如果键已存在则抛出异常。
        try (RedisConnectionProvider.RedisConnectionContext context =
                     RedisConnectionProvider.open(Chat2DBContext.getConnectInfo())) {
            RedisAsyncCommands<String, String> commands = context.connection().async();
            if (StringUtils.isBlank(keyName)) {
                throw new IllegalArgumentException("Redis key 不能为空");
            }
            selectDatabase(commands, databaseName)
                    .thenCompose(ignored -> commands.exists(keyName).toCompletableFuture())
                    .thenApply(exists -> {
                        if (exists > 0) {
                            throw new IllegalArgumentException("Redis key 已存在: " + keyName);
                        }
                        return null;
                    })
                    .thenCompose(ignored -> writeValue(commands, keyName, keyType, value))
                    .thenCompose(ignored -> commands.exists(keyName).toCompletableFuture())
                    .thenApply(exists -> {
                        if (exists == 0) {
                            throw new IllegalArgumentException("Redis key value 不能为空");
                        }
                        return null;
                    })
                    .thenCompose(ignored -> applyTtl(commands, keyName, ttl))
                    .join();
        }
    }

    @Override
    public void updateKey(String databaseName, String originalKey, String updateKey, String keyType, Object value,
                          Long ttl) {
        try (RedisConnectionProvider.RedisConnectionContext context =
                     RedisConnectionProvider.open(Chat2DBContext.getConnectInfo())) {
            RedisAsyncCommands<String, String> commands = context.connection().async();
            String targetKey = StringUtils.defaultIfBlank(updateKey, originalKey);
            selectDatabase(commands, databaseName)
                    .thenCompose(ignored -> {
                        if (!StringUtils.equals(originalKey, targetKey)) {
                            return commands.rename(originalKey, targetKey).toCompletableFuture();
                        }
                        return CompletableFuture.completedFuture(null);
                    })
                    .thenCompose(ignored -> commands.del(targetKey).toCompletableFuture())
                    .thenCompose(ignored -> writeValue(commands, targetKey, keyType, value))
                    .thenCompose(ignored -> applyTtl(commands, targetKey, ttl))
                    .join();
        }
    }

    @Override
    public void deleteKey(String databaseName, String keyName) {
        // 删除指定 Redis 键，空键名将被忽略。
        try (RedisConnectionProvider.RedisConnectionContext context =
                     RedisConnectionProvider.open(Chat2DBContext.getConnectInfo())) {
            RedisAsyncCommands<String, String> commands = context.connection().async();
            selectDatabase(commands, databaseName)
                    .thenCompose(ignored -> {
                        if (StringUtils.isNotBlank(keyName)) {
                            return commands.del(keyName).toCompletableFuture();
                        }
                        return CompletableFuture.completedFuture(null);
                    })
                    .join();
        }
    }

    @Override
    public void monitor(String databaseName, Consumer<String> lineConsumer, BooleanSupplier running) {
        // 使用原生 Redis MONITOR 命令，输出实时命令流。
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        RedisConnectionProvider.RedisConnectionInfo connectionInfo = RedisConnectionProvider.parse(connectInfo);
        Session session = null;
        String host = connectionInfo.host();
        int port = connectionInfo.port();
        if (connectInfo.getSsh() != null && connectInfo.getSsh().isUse()) {
            connectInfo.getSsh().setRHost(host);
            connectInfo.getSsh().setRPort(Integer.toString(port));
            session = SSHManager.getSSHSession(connectInfo.getSsh());
            host = "127.0.0.1";
            port = Integer.parseInt(connectInfo.getSsh().getLocalPort());
        }
        try (Socket socket = new Socket(host, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                     StandardCharsets.UTF_8));
             OutputStream writer = socket.getOutputStream()) {
            socket.setSoTimeout(1000);
            authenticate(connectInfo, writer, reader);
            selectMonitorDatabase(databaseName, writer, reader);
            writeCommand(writer, "MONITOR");
            expectStatus(reader, "OK");
            while (running.getAsBoolean() && !socket.isClosed()) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        return;
                    }
                    lineConsumer.accept(redactMonitorLine(stripRespSimpleString(line)));
                } catch (java.net.SocketTimeoutException ignored) {
                    // allow cancellation check
                }
            }
        } catch (Exception e) {
            if (running.getAsBoolean()) {
                throw new IllegalStateException("Redis monitor failed: " + e.getMessage(), e);
            }
        } finally {
            closeSshSession(session);
        }
    }

    /**
     * 使用 SCAN 命令分批获取 Redis 键信息，并将结果按批次回传。
     */
    private RedisKeyScanResult scanKeyInfo(RedisAsyncCommands<String, String> commands, String pattern, String cursor,
                                           long count, Consumer<List<RedisKeyInfo>> batchConsumer) {
        long emitted = 0;
        ScanArgs scanArgs = new ScanArgs().limit(KEY_SCAN_BATCH_SIZE);
        if (StringUtils.isNotBlank(pattern)) {
            scanArgs.match(pattern);
        }
        ScanCursor scanCursor = buildScanCursor(cursor);
        do {
            KeyScanCursor<String> result = commands.scan(scanCursor, scanArgs).toCompletableFuture().join();
            List<String> keys = result.getKeys();
            if (!keys.isEmpty()) {
                List<CompletableFuture<RedisKeyInfo>> futures = new ArrayList<>(keys.size());
                keys.forEach(key -> futures.add(buildKeyInfo(commands, key, false)));
                List<RedisKeyInfo> items = resolveBatch(futures);
                emitted += items.size();
                batchConsumer.accept(items);
            }
            scanCursor = ScanCursor.of(result.getCursor());
            scanCursor.setFinished(result.isFinished());
        } while (!scanCursor.isFinished() && emitted < count);
        boolean hasMore = !scanCursor.isFinished();
        return RedisKeyScanResult.builder()
                .cursor(hasMore ? scanCursor.getCursor() : "0")
                .hasMore(hasMore)
                .total(Math.toIntExact(Math.min(emitted, Integer.MAX_VALUE)))
                .build();
    }

    private RedisKeyScanResult queryExactKey(RedisAsyncCommands<String, String> commands, String key,
                                             Consumer<List<RedisKeyInfo>> batchConsumer) {
        long exists = commands.exists(key).toCompletableFuture().join();
        if (exists > 0) {
            batchConsumer.accept(List.of(buildKeyInfo(commands, key, false).join()));
        }
        return RedisKeyScanResult.builder()
                .cursor("0")
                .hasMore(false)
                .total(exists > 0 ? 1 : 0)
                .build();
    }

    private ScanCursor buildScanCursor(String cursor) {
        if (isInitialCursor(cursor)) {
            return ScanCursor.INITIAL;
        }
        return ScanCursor.of(cursor);
    }

    private boolean isInitialCursor(String cursor) {
        return StringUtils.isBlank(cursor) || "0".equals(cursor);
    }

    private boolean isExactKeyPattern(String pattern) {
        return StringUtils.isNotBlank(pattern)
                && !StringUtils.containsAny(pattern, '*', '?', '[', ']');
    }

    /**
     * 构建键信息。
     *
     * @param fullValue true 读取完整值（详情场景），false 读取预览摘要（列表场景）
     */
    private CompletableFuture<RedisKeyInfo> buildKeyInfo(RedisAsyncCommands<String, String> commands, String key,
                                                        boolean fullValue) {
        return getTypeAsync(commands, key).thenCompose(type -> {
            CompletableFuture<Object> value = fullValue ? readValue(commands, key, type)
                    : previewValue(commands, key, type);
            CompletableFuture<Long> ttl = commands.ttl(key).toCompletableFuture()
                    .exceptionally(e -> null);
            CompletableFuture<Long> size = commands.memoryUsage(key).toCompletableFuture()
                    .exceptionally(e -> null);
            return CompletableFuture.allOf(value, ttl, size)
                    .thenApply(ignored -> RedisKeyInfo.builder()
                            .name(key)
                            .type(type)
                            .value(value.join())
                            .ttl(ttl.join())
                            .size(size.join())
                            .build());
        }).exceptionally(e -> RedisKeyInfo.builder()
                .name(key)
                .type("unknown")
                .value("")
                .build());
    }

    /**
     * 预览键值内容，仅返回简短摘要用于界面展示。
     */
    private CompletableFuture<Object> previewValue(RedisAsyncCommands<String, String> commands, String key,
                                                   String type) {
        try {
            return switch (StringUtils.defaultString(type).toLowerCase()) {
                case "string" -> commands.get(key).toCompletableFuture().thenApply(this::abbreviate);
                case "hash" -> commands.hgetall(key).toCompletableFuture().thenApply(this::previewMap);
                case "list" -> commands.lrange(key, 0, VALUE_PREVIEW_LIMIT - 1).toCompletableFuture()
                        .thenApply(this::previewList);
                case "set" -> commands.srandmember(key, VALUE_PREVIEW_LIMIT).toCompletableFuture()
                        .thenApply(this::previewList);
                case "zset" -> commands.zrange(key, 0, VALUE_PREVIEW_LIMIT - 1).toCompletableFuture()
                        .thenApply(this::previewList);
                default -> CompletableFuture.completedFuture("");
            };
        } catch (Exception e) {
            return CompletableFuture.completedFuture("");
        }
    }

    /**
     * 等待一批异步键信息构建完成，并返回完整结果。
     */
    private List<RedisKeyInfo> resolveBatch(List<CompletableFuture<RedisKeyInfo>> batch) {
        CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).join();
        return batch.stream().map(CompletableFuture::join).toList();
    }

    /**
     * 根据键类型异步读取完整值。
     */
    private CompletableFuture<Object> readValue(RedisAsyncCommands<String, String> commands, String key, String type) {
        try {
            return switch (StringUtils.defaultString(type).toLowerCase()) {
                case "string" -> commands.get(key).toCompletableFuture().thenApply(v -> v);
                case "hash" -> commands.hgetall(key).toCompletableFuture().thenApply(v -> v);
                case "list" -> commands.lrange(key, 0, -1).toCompletableFuture().thenApply(v -> v);
                case "set" -> commands.smembers(key).toCompletableFuture().thenApply(v -> v);
                case "zset" -> commands.zrange(key, 0, -1).toCompletableFuture().thenApply(v -> v);
                default -> CompletableFuture.completedFuture("");
            };
        } catch (Exception e) {
            log.warn("Redis key value read failed, key={}", key, e);
            return CompletableFuture.completedFuture("");
        }
    }

    /**
     * 根据键类型异步写入值。
     */
    private CompletableFuture<Void> writeValue(RedisAsyncCommands<String, String> commands, String key, String keyType,
                                               Object value) {
        return switch (StringUtils.defaultString(keyType).toLowerCase()) {
            case "string" -> commands.set(key, value == null ? "" : String.valueOf(value))
                    .toCompletableFuture().thenApply(ignored -> null);
            case "hash" -> {
                Map<String, String> map = toStringMap(value);
                if (!map.isEmpty()) {
                    yield commands.hset(key, map).toCompletableFuture().thenApply(ignored -> null);
                }
                yield CompletableFuture.completedFuture(null);
            }
            case "list" -> {
                List<String> values = toStringList(value);
                if (!values.isEmpty()) {
                    yield commands.rpush(key, values.toArray(new String[0]))
                            .toCompletableFuture().thenApply(ignored -> null);
                }
                yield CompletableFuture.completedFuture(null);
            }
            case "set" -> {
                List<String> values = toStringList(value);
                if (!values.isEmpty()) {
                    yield commands.sadd(key, values.toArray(new String[0]))
                            .toCompletableFuture().thenApply(ignored -> null);
                }
                yield CompletableFuture.completedFuture(null);
            }
            case "zset" -> {
                List<String> values = toStringList(value);
                yield addZsetMembers(commands, key, values, 0);
            }
            default -> throw new IllegalArgumentException("暂不支持编辑 Redis 类型: " + keyType);
        };
    }

    /**
     * 按顺序异步添加有序集合成员，保证添加顺序。
     */
    private CompletableFuture<Void> addZsetMembers(RedisAsyncCommands<String, String> commands, String key,
                                                   List<String> values, int index) {
        if (index >= values.size()) {
            return CompletableFuture.completedFuture(null);
        }
        return commands.zadd(key, index, values.get(index)).toCompletableFuture()
                .thenCompose(ignored -> addZsetMembers(commands, key, values, index + 1));
    }

    /**
     * 异步将 TTL 应用到指定键，如 ttl 为 null 或负值则取消过期时间。
     */
    private CompletableFuture<Void> applyTtl(RedisAsyncCommands<String, String> commands, String key, Long ttl) {
        if (ttl == null || ttl < 0) {
            return commands.persist(key).toCompletableFuture().thenApply(ignored -> null);
        }
        if (ttl > 0) {
            return commands.expire(key, ttl).toCompletableFuture().thenApply(ignored -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 将任意对象转换为字符串键值映射，供 Hash 类型写入使用。
     */
    private Map<String, String> toStringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            return result;
        }
        map.forEach((field, fieldValue) -> {
            if (field != null) {
                result.put(String.valueOf(field), fieldValue == null ? "" : String.valueOf(fieldValue));
            }
        });
        return result;
    }

    private List<String> toStringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(item -> item == null ? "" : String.valueOf(item)).toList();
        }
        if (value == null) {
            return List.of();
        }
        return List.of(String.valueOf(value));
    }

    /**
     * 通过原始 RESP 协议发送 AUTH 命令并验证返回结果。
     */
    private void authenticate(ConnectInfo connectInfo, OutputStream writer, BufferedReader reader)
            throws IOException {
        if (StringUtils.isBlank(connectInfo.getPassword())) {
            return;
        }
        if (StringUtils.isNotBlank(connectInfo.getUser())) {
            writeCommand(writer, "AUTH", connectInfo.getUser(), connectInfo.getPassword());
        } else {
            writeCommand(writer, "AUTH", connectInfo.getPassword());
        }
        expectStatus(reader, "OK");
    }

    /**
     * 为监控连接选择指定数据库。
     */
    private void selectMonitorDatabase(String databaseName, OutputStream writer, BufferedReader reader)
            throws IOException {
        if (StringUtils.isBlank(databaseName)) {
            return;
        }
        writeCommand(writer, "SELECT", databaseName);
        expectStatus(reader, "OK");
    }

    private void writeCommand(OutputStream writer, String... args) throws IOException {
        writer.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String arg : args) {
            String value = StringUtils.defaultString(arg);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writer.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            writer.write(bytes);
            writer.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        writer.flush();
    }

    private void expectStatus(BufferedReader reader, String expected) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("Redis connection closed");
        }
        if (line.startsWith("-")) {
            throw new IOException(line.substring(1));
        }
        String value = stripRespSimpleString(line);
        if (!expected.equalsIgnoreCase(value)) {
            throw new IOException("Unexpected Redis response: " + value);
        }
    }

    private String stripRespSimpleString(String line) {
        if (StringUtils.isBlank(line)) {
            return "";
        }
        if (line.charAt(0) == '+' || line.charAt(0) == '$') {
            return line.substring(1);
        }
        return line;
    }

    private String redactMonitorLine(String line) {
        return line.replaceAll("(?i)(\"AUTH\"\\s+(\"[^\"]*\"\\s+)?\")([^\"]*)(\")", "$1(redacted)$4");
    }

    private void closeSshSession(Session session) {
        if (session == null) {
            return;
        }
        try {
            session.delPortForwardingL(Integer.parseInt(session.getPortForwardingL()[0].split(":")[0]));
        } catch (JSchException | RuntimeException e) {
            // ignore
        }
        session.disconnect();
    }

    private String previewMap(Map<String, String> values) {
        StringJoiner joiner = new StringJoiner(", ", "[", values.size() > VALUE_PREVIEW_LIMIT ? ", ...]" : "]");
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (index++ >= VALUE_PREVIEW_LIMIT) {
                break;
            }
            joiner.add(entry.getKey() + ":" + abbreviate(entry.getValue()));
        }
        return joiner.toString();
    }

    private String previewList(List<String> values) {
        StringJoiner joiner = new StringJoiner(", ", "[", values.size() > VALUE_PREVIEW_LIMIT ? ", ...]" : "]");
        int limit = Math.min(values.size(), VALUE_PREVIEW_LIMIT);
        for (int i = 0; i < limit; i++) {
            joiner.add(abbreviate(values.get(i)));
        }
        return joiner.toString();
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= VALUE_TEXT_LIMIT) {
            return value;
        }
        return value.substring(0, VALUE_TEXT_LIMIT) + "...";
    }

    private CompletableFuture<Void> selectDatabase(RedisAsyncCommands<String, String> commands, String databaseName) {
        if (StringUtils.isBlank(databaseName)) {
            return CompletableFuture.completedFuture(null);
        }
        return commands.select(Integer.parseInt(databaseName)).toCompletableFuture().thenApply(ignored -> null);
    }

    /**
     * 异步获取键类型，失败时默认返回 "string"。
     */
    private CompletableFuture<String> getTypeAsync(RedisAsyncCommands<String, String> commands, String key) {
        return commands.type(key).toCompletableFuture()
                .thenApply(type -> StringUtils.isNotBlank(type) ? type : "string")
                .exceptionally(e -> {
                    log.error("type获取失败", e);
                    return "string";
                });
    }
}
