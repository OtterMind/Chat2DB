package ai.chat2db.plugin.redis;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSqlBuilderTest {

    @AfterEach
    void tearDown() {
        Chat2DBContext.close();
    }

    private void stubKeyType(String keyType) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("REDIS");
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(typeStubConnection(keyType));
        Chat2DBContext.putContext(connectInfo);
    }

    private Header header(String name) {
        return Header.builder().name(name).dataType("VARCHAR").build();
    }

    private ResultOperation operation(String type, List<String> oldData, List<String> newData) {
        ResultOperation operation = new ResultOperation();
        operation.setType(type);
        operation.setOldDataList(oldData);
        operation.setDataList(newData);
        return operation;
    }

    private QueryResponse queryResponse(String tableName, List<Header> headers, List<ResultOperation> operations,
            Map<String, Object> extra) {
        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setTableName(tableName);
        queryResponse.setHeaderList(headers);
        queryResponse.setOperations(operations);
        queryResponse.setExtra(extra);
        return queryResponse;
    }

    @Test
    void buildByQueryResultGeneratesHashMutationCommands() {
        stubKeyType("hash");
        QueryResponse queryResult = queryResponse("k",
                List.of(header("#"), header("field"), header("value")),
                List.of(operation("UPDATE", List.of("1", "f1", "v1old"), List.of("1", "f1", "v1new")),
                        operation("DELETE", List.of("2", "f2", "v2"), null),
                        operation("CREATE", null, List.of("3", "f3", "v3"))),
                new HashMap<>());

        assertEquals("MULTI \n" + "HSET 'k' 'f1' 'v1new'\n" + "HDEL 'k' 'f2'\n" + "HSET 'k' 'f3' 'v3'\n" + "EXEC",
                RedisSqlBuilder.getInstance().buildByQueryResult(queryResult));
    }

    @Test
    void buildByQueryResultGeneratesSetMutationCommands() {
        stubKeyType("set");
        QueryResponse queryResult = queryResponse("k",
                List.of(header("#"), header("value")),
                List.of(operation("UPDATE", List.of("1", "a"), List.of("1", "b")),
                        operation("DELETE", List.of("2", "c"), null)),
                new HashMap<>());

        assertEquals("MULTI \n" + "SREM 'k' 'a'\n" + "SADD 'k' 'b'\n" + "SREM 'k' 'c'\n" + "EXEC",
                RedisSqlBuilder.getInstance().buildByQueryResult(queryResult));
    }

    @Test
    void buildByQueryResultGeneratesZSetMutationCommands() {
        stubKeyType("zset");
        QueryResponse queryResult = queryResponse("k",
                List.of(header("#"), header("value"), header("score")),
                List.of(operation("CREATE", null, List.of("1", "v", "1.5")),
                        operation("DELETE", List.of("2", "w", "2.0"), null)),
                new HashMap<>());

        assertEquals("MULTI \n" + "ZADD 'k' '1.5' 'v'\n" + "ZREM 'k' 'w'\n" + "EXEC",
                RedisSqlBuilder.getInstance().buildByQueryResult(queryResult));
    }

    @Test
    void buildByQueryResultGeneratesListMutationCommands() {
        stubKeyType("list");
        QueryResponse queryResult = queryResponse("k",
                List.of(header("#"), header("value")),
                List.of(operation("UPDATE", List.of("2", "a"), List.of("2", "b")),
                        operation("CREATE", null, List.of("3", "c")),
                        operation("DELETE", List.of("1", "x"), null)),
                new HashMap<>());

        // DELETE on row 1 is positional now (tombstone via LSET+LREM) so duplicate
        // values cannot remove the wrong occurrence.
        String script = RedisSqlBuilder.getInstance().buildByQueryResult(queryResult);
        String[] lines = script.split("\\n");

        assertEquals("MULTI ", lines[0]);
        assertEquals("LSET 'k' 1 'b'", lines[1]);
        assertEquals("RPUSH 'k' 'c'", lines[2]);
        String tombstone = lines[3].substring(lines[3].lastIndexOf(' ') + 1);
        assertTrue(tombstone.matches("'__chat2db_deleted__[0-9a-f-]+'"));
        assertEquals("LSET 'k' 0 " + tombstone, lines[3]);
        assertEquals("LREM 'k' 1 " + tombstone, lines[4]);
        assertEquals("EXEC", lines[5]);
    }

    @Test
    void buildByQueryResultGeneratesStringMutationCommands() {
        stubKeyType("string");
        QueryResponse queryResult = queryResponse("k",
                List.of(header("#"), header("value")),
                List.of(operation("UPDATE", List.of("1", "v1"), List.of("1", "v2"))),
                new HashMap<>());

        assertEquals("MULTI \n" + "SET 'k' 'v2'\n" + "EXEC",
                RedisSqlBuilder.getInstance().buildByQueryResult(queryResult));
    }

    @Test
    void buildByQueryResultQuotesRenameAndSkipsSentinelTtl() {
        stubKeyType("hash");
        Map<String, Object> extra = new HashMap<>();
        extra.put("keyType", "hash");
        extra.put("key", "new key");
        extra.put("ttl", "-1");
        QueryResponse queryResult = queryResponse("old key",
                List.of(header("#"), header("field"), header("value")),
                List.of(operation("UPDATE", List.of("1", "f1", "v1"), List.of("1", "f1", "v2"))),
                extra);

        String script = RedisSqlBuilder.getInstance().buildByQueryResult(queryResult);

        assertEquals("MULTI \n" + "RENAME 'old key' 'new key'\n" + "HSET 'new key' 'f1' 'v2'\n" + "EXEC", script);
        assertFalse(script.contains("EXPIRE"));
    }

    @Test
    void buildByQueryResultExpiresOnlyPositiveTtlAndSkipsNoopRename() {
        stubKeyType("hash");
        Map<String, Object> extra = new HashMap<>();
        extra.put("keyType", "hash");
        extra.put("key", "k");
        extra.put("ttl", "100");
        QueryResponse queryResult = queryResponse("k",
                List.of(header("#"), header("field"), header("value")),
                List.of(operation("UPDATE", List.of("1", "f1", "v1"), List.of("1", "f1", "v2"))),
                extra);

        String script = RedisSqlBuilder.getInstance().buildByQueryResult(queryResult);

        assertEquals("MULTI \n" + "EXPIRE 'k' 100\n" + "HSET 'k' 'f1' 'v2'\n" + "EXEC", script);
        assertFalse(script.contains("RENAME"));
    }

    @Test
    void buildByQueryResultReturnsEmptyWhenNoOperations() {
        QueryResponse queryResult = queryResponse("k", List.of(header("value")), List.of(), new HashMap<>());

        assertTrue(RedisSqlBuilder.getInstance().buildByQueryResult(queryResult).isEmpty());
    }

    private static Connection typeStubConnection(String keyType) {
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> typeStubStatement(keyType);
            case "isClosed" -> false;
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static PreparedStatement typeStubStatement(String keyType) {
        final ResultSet[] resultSet = new ResultSet[1];
        return proxy(PreparedStatement.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "execute":
                    resultSet[0] = singleValueResultSet(keyType);
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

    private static ResultSet singleValueResultSet(String value) {
        return proxy(ResultSet.class, new InvocationHandler() {
            private boolean beforeFirst = true;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                switch (method.getName()) {
                    case "next":
                        if (beforeFirst) {
                            beforeFirst = false;
                            return true;
                        }
                        return false;
                    case "getString":
                    case "getObject":
                        return value;
                    case "close":
                        return null;
                    default:
                        return defaultValue(method.getReturnType());
                }
            }
        });
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
