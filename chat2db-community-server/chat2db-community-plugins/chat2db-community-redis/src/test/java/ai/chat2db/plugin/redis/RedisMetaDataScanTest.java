package ai.chat2db.plugin.redis;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.plugin.redis.config.RedisScanConfig;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisMetaDataScanTest {

    @AfterEach
    void tearDown() {
        Chat2DBContext.close();
    }

    private ScanStub setup(Map<String, String> keyTypes, Map<String, ScanBatch> scanBatches) {
        ScanStub stub = new ScanStub(keyTypes, scanBatches);
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("REDIS");
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(stub.connection());
        Chat2DBContext.putContext(connectInfo);
        return stub;
    }

    private record ScanBatch(String nextCursor, List<String> keys) {
    }

    @Test
    void tablesScansAllBatchesAndDedupesKeys() {
        ScanStub stub = setup(Map.of(), Map.of(
                "0", new ScanBatch("5", List.of("a", "b", "a")),
                "5", new ScanBatch("0", List.of("c", "a"))));

        List<Table> tables = new RedisMetaData().tables(stub.connection(), null, null, null);

        assertEquals(List.of("a", "b", "c"), tables.stream().map(Table::getName).toList());
        assertEquals(2, stub.scanCalls());
    }

    @Test
    void matchIteratesAcrossBatchesAndDedupesKeys() {
        ScanStub stub = setup(Map.of("k1", "string", "k2", "string"), Map.of(
                "0", new ScanBatch("3", List.of("k1", "k1")),
                "3", new ScanBatch("0", List.of("k1", "k2"))));

        List<RedisKey> keys = new RedisMetaData().keys(stub.connection(), null, null, "foo");

        assertEquals(List.of("k1", "k2"), keys.stream().map(RedisKey::getName).toList());
        assertEquals(2, stub.scanCalls());
        assertEquals(1, stub.commandCount("type 'k1'"));
    }

    @Test
    void matchStopsAfterScanCallBudgetInsteadOfRecursing() {
        Map<String, ScanBatch> batches = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            batches.put(String.valueOf(i), new ScanBatch(String.valueOf(i + 1), List.of()));
        }
        ScanStub stub = setup(Map.of(), batches);

        List<RedisKey> keys = new RedisMetaData().keys(stub.connection(), null, null, "foo");

        assertEquals(0, keys.size());
        assertEquals(RedisScanConfig.DEFAULT.maxScanCallsPerRequest(), stub.scanCalls());
    }

    @Test
    void findTopDedupesKeysWithinOneBatch() {
        ScanStub stub = setup(Map.of("k1", "string"), Map.of(
                "0", new ScanBatch("0", List.of("k1", "k1"))));

        List<RedisKey> keys = new RedisMetaData().keys(stub.connection(), null, null, " ");

        assertEquals(1, keys.size());
        assertEquals("k1", keys.get(0).getName());
        assertEquals(1, stub.commandCount("type 'k1'"));
    }

    private static final class ScanStub {

        private final Map<String, String> keyTypes;
        private final Map<String, ScanBatch> scanBatches;
        private final List<String> commands = new ArrayList<>();

        private ScanStub(Map<String, String> keyTypes, Map<String, ScanBatch> scanBatches) {
            this.keyTypes = keyTypes;
            this.scanBatches = scanBatches;
        }

        Connection connection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> statement((String) args[0]);
                case "isClosed" -> false;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        long scanCalls() {
            return commands.stream().filter(command -> command.startsWith("scan ")).count();
        }

        long commandCount(String command) {
            return commands.stream().filter(command::equals).count();
        }

        private PreparedStatement statement(String sql) {
            final ResultSet[] resultSet = new ResultSet[1];
            return proxy(PreparedStatement.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "execute":
                        commands.add(sql);
                        resultSet[0] = resultSetFor(sql);
                        return true;
                    case "getResultSet":
                        return resultSet[0];
                    case "close":
                        return null;
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        }

        private ResultSet resultSetFor(String sql) {
            if (sql.startsWith("scan ")) {
                String cursor = sql.split(" ")[1];
                ScanBatch batch = scanBatches.getOrDefault(cursor, new ScanBatch("0", List.of()));
                return resultSet(List.<Object[]>of(new Object[] {batch.nextCursor(), batch.keys()}));
            }
            if (sql.startsWith("EXISTS ")) {
                return resultSet(List.<Object[]>of(new Object[] {keyTypes.containsKey(unquote(sql)) ? 1 : 0}));
            }
            if (sql.startsWith("type ")) {
                return resultSet(List.<Object[]>of(new Object[] {keyTypes.get(unquote(sql))}));
            }
            if (sql.startsWith("TTL ")) {
                return resultSet(List.<Object[]>of(new Object[] {"-1"}));
            }
            if (sql.startsWith("GET ")) {
                return resultSet(List.<Object[]>of(new Object[] {"v"}));
            }
            return resultSet(List.of());
        }

        private String unquote(String sql) {
            return sql.substring(sql.indexOf('\'') + 1, sql.lastIndexOf('\''));
        }

        private ResultSet resultSet(List<Object[]> rows) {
            return proxy(ResultSet.class, new InvocationHandler() {
                private int index = -1;

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    switch (method.getName()) {
                        case "next":
                            return ++index < rows.size();
                        case "getObject": {
                            Object column = args[0];
                            if (column instanceof Integer) {
                                return rows.get(index)[(Integer) column - 1];
                            }
                            return rows.get(index)[0];
                        }
                        case "getString": {
                            Object value = rows.get(index)[(Integer) args[0] - 1];
                            return value == null ? null : value.toString();
                        }
                        case "getInt":
                            return ((Number) rows.get(index)[(Integer) args[0] - 1]).intValue();
                        case "close":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }
}
