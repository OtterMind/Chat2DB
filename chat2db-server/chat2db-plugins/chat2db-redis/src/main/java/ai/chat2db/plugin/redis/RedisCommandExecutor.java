package ai.chat2db.plugin.redis;

import ai.chat2db.spi.CommandExecutor;
import ai.chat2db.spi.ValueHandler;
import ai.chat2db.spi.enums.DataTypeEnum;
import ai.chat2db.spi.enums.SqlTypeEnum;
import ai.chat2db.spi.model.Command;
import ai.chat2db.spi.model.ExecuteResult;
import ai.chat2db.spi.model.Header;
import ai.chat2db.spi.sql.Chat2DBContext;
import cn.hutool.core.date.TimeInterval;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RedisCommandExecutor implements CommandExecutor {

    private static final int DEFAULT_SCAN_COUNT = 1000;

    @Override
    public List<ExecuteResult> execute(Command command) {
        List<RedisCommandParser.StatementPosition> statements =
                RedisCommandParser.splitStatementPositions(command.getScript());
        List<ExecuteResult> results = new ArrayList<>();
        if (CollectionUtils.isEmpty(statements)) {
            return results;
        }
        try (RedisConnectionProvider.RedisConnectionContext context =
                     RedisConnectionProvider.open(Chat2DBContext.getConnectInfo())) {
            RedisCommands<String, String> commands = context.connection().sync();
            for (int i = 0; i < statements.size(); i++) {
                RedisCommandParser.StatementPosition statement = statements.get(i);
                ExecuteResult result = executeStatement(commands, statement.statement());
                result.setStatementIndex(i + 1);
                result.setStatementStartLine(getStatementLine(command.getScriptStartLine(), statement.startLine()));
                result.setStatementEndLine(getStatementLine(command.getScriptStartLine(), statement.endLine()));
                result.setOriginalSql(statement.statement());
                results.add(result);
            }
        }
        return results;
    }

    @Override
    public ExecuteResult executeUpdate(String sql, Connection connection, int n) throws SQLException {
        return execute(sql, connection, true, null, null, null);
    }

    @Override
    public ExecuteResult execute(String sql, Connection connection, boolean limitRowSize, Integer offset, Integer count,
                                 ValueHandler valueHandler) throws SQLException {
        try (RedisConnectionProvider.RedisConnectionContext context =
                     RedisConnectionProvider.open(Chat2DBContext.getConnectInfo())) {
            return executeStatement(context.connection().sync(), sql);
        }
    }

    private ExecuteResult executeStatement(RedisCommands<String, String> commands, String statement) {
        TimeInterval timer = new TimeInterval();
        ExecuteResult result = ExecuteResult.builder()
                .sql(statement)
                .success(Boolean.TRUE)
                .description("Success")
                .sqlType(SqlTypeEnum.UNKNOWN.getCode())
                .build();
        try {
            List<String> args = RedisCommandParser.tokenize(statement);
            if (CollectionUtils.isEmpty(args)) {
                result.setUpdateCount(0);
                return result;
            }
            String command = args.get(0).toUpperCase(Locale.ROOT);
            switch (command) {
                case "PING" -> oneValue(result, "result", commands.ping());
                case "SELECT" -> status(result, commands.select(parseInt(args, 1, "database")));
                case "CONFIG" -> executeConfig(commands, args, result);
                case "DBSIZE" -> oneValue(result, "size", commands.dbsize());
                case "TYPE" -> oneValue(result, "type", commands.type(required(args, 1, "key")));
                case "EXISTS" -> oneValue(result, "exists", commands.exists(tail(args, 1)));
                case "TTL" -> oneValue(result, "ttl", commands.ttl(required(args, 1, "key")));
                case "EXPIRE" -> oneValue(result, "result",
                        commands.expire(required(args, 1, "key"), parseLong(args, 2, "seconds")));
                case "DEL" -> oneValue(result, "deleted", commands.del(tail(args, 1)));
                case "GET" -> oneValue(result, "value", commands.get(required(args, 1, "key")));
                case "SET" -> status(result, commands.set(required(args, 1, "key"), required(args, 2, "value")));
                case "KEYS" -> listValues(result, "key", commands.keys(required(args, 1, "pattern")));
                case "SCAN" -> scan(commands, args, result);
                case "HGET" -> oneValue(result, "value", commands.hget(required(args, 1, "key"),
                        required(args, 2, "field")));
                case "HGETALL" -> mapValues(result, "field", "value", commands.hgetall(required(args, 1, "key")));
                case "HSET" -> oneValue(result, "added", commands.hset(required(args, 1, "key"),
                        fieldValueMap(args, 2)));
                case "LRANGE" -> listValues(result, "value", commands.lrange(required(args, 1, "key"),
                        parseLong(args, 2, "start"), parseLong(args, 3, "stop")));
                case "LPUSH" -> oneValue(result, "length", commands.lpush(required(args, 1, "key"), tail(args, 2)));
                case "RPUSH" -> oneValue(result, "length", commands.rpush(required(args, 1, "key"), tail(args, 2)));
                case "SMEMBERS" -> listValues(result, "member", commands.smembers(required(args, 1, "key")));
                case "SADD" -> oneValue(result, "added", commands.sadd(required(args, 1, "key"), tail(args, 2)));
                case "ZRANGE" -> listValues(result, "member", commands.zrange(required(args, 1, "key"),
                        parseLong(args, 2, "start"), parseLong(args, 3, "stop")));
                case "ZADD" -> oneValue(result, "added", commands.zadd(required(args, 1, "key"),
                        parseDouble(args, 2, "score"), required(args, 3, "member")));
                default -> throw new IllegalArgumentException("Unsupported Redis command: " + command);
            }
        } catch (Exception e) {
            result.setSuccess(Boolean.FALSE);
            result.setMessage(e.getMessage());
        } finally {
            result.setDuration(timer.interval());
        }
        return result;
    }

    private int getStatementLine(Integer scriptStartLine, int statementLine) {
        return statementLine + Math.max((scriptStartLine == null ? 1 : scriptStartLine) - 1, 0);
    }

    private void executeConfig(RedisCommands<String, String> commands, List<String> args, ExecuteResult result) {
        if (args.size() >= 3 && "GET".equalsIgnoreCase(args.get(1))) {
            mapValues(result, "parameter", "value", commands.configGet(args.get(2)));
            return;
        }
        throw new IllegalArgumentException("Only CONFIG GET is supported");
    }

    private void scan(RedisCommands<String, String> commands, List<String> args, ExecuteResult result) {
        String cursor = ScanCursor.INITIAL.getCursor();
        ScanArgs scanArgs = new ScanArgs().limit(DEFAULT_SCAN_COUNT);
        int optionStart = 1;
        if (args.size() > 1) {
            String firstArg = args.get(1);
            if (StringUtils.isNumeric(firstArg)) {
                cursor = firstArg;
                optionStart = 2;
            } else if (!isScanOption(firstArg)) {
                scanArgs.match("*" + firstArg + "*");
                optionStart = 2;
            }
        }
        for (int i = optionStart; i < args.size(); i++) {
            String token = args.get(i);
            if ("MATCH".equalsIgnoreCase(token)) {
                scanArgs.match(required(args, ++i, "pattern"));
            } else if ("COUNT".equalsIgnoreCase(token)) {
                scanArgs.limit(parseLong(args, ++i, "count"));
            }
        }
        KeyScanCursor<String> scan = commands.scan(ScanCursor.of(cursor), scanArgs);
        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList("cursor", scan.getCursor()));
        for (String key : scan.getKeys()) {
            rows.add(Arrays.asList("key", key));
        }
        result.setHeaderList(headers("type", "value"));
        result.setDataList(rows);
        result.setSqlType(SqlTypeEnum.SELECT.getCode());
        result.setPageNo(1);
        result.setPageSize(rows.size());
        result.setHasNextPage(!scan.isFinished());
        result.setFuzzyTotal(scan.isFinished() ? Integer.toString(rows.size()) : rows.size() + "+");
    }

    private boolean isScanOption(String token) {
        return "MATCH".equalsIgnoreCase(token) || "COUNT".equalsIgnoreCase(token);
    }

    private void status(ExecuteResult result, String value) {
        result.setUpdateCount(1);
        oneValue(result, "result", value);
    }

    private void oneValue(ExecuteResult result, String name, Object value) {
        result.setHeaderList(headers(name));
        result.setDataList(List.of(List.of(String.valueOf(value))));
        result.setSqlType(SqlTypeEnum.SELECT.getCode());
        result.setPageNo(1);
        result.setPageSize(1);
        result.setHasNextPage(Boolean.FALSE);
        result.setFuzzyTotal("1");
    }

    private void listValues(ExecuteResult result, String name, Iterable<?> values) {
        List<List<String>> rows = new ArrayList<>();
        for (Object value : values) {
            rows.add(List.of(String.valueOf(value)));
        }
        result.setHeaderList(headers(name));
        result.setDataList(rows);
        result.setSqlType(SqlTypeEnum.SELECT.getCode());
        result.setPageNo(1);
        result.setPageSize(rows.size());
        result.setHasNextPage(Boolean.FALSE);
        result.setFuzzyTotal(Integer.toString(rows.size()));
    }

    private void mapValues(ExecuteResult result, String keyName, String valueName, Map<String, String> values) {
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rows.add(Arrays.asList(entry.getKey(), entry.getValue()));
        }
        result.setHeaderList(headers(keyName, valueName));
        result.setDataList(rows);
        result.setSqlType(SqlTypeEnum.SELECT.getCode());
        result.setPageNo(1);
        result.setPageSize(rows.size());
        result.setHasNextPage(Boolean.FALSE);
        result.setFuzzyTotal(Integer.toString(rows.size()));
    }

    private List<Header> headers(String... names) {
        List<Header> headers = new ArrayList<>();
        for (String name : names) {
            headers.add(Header.builder().name(name).dataType(DataTypeEnum.STRING.getCode()).build());
        }
        return headers;
    }

    private String required(List<String> args, int index, String name) {
        if (args.size() <= index || StringUtils.isBlank(args.get(index))) {
            throw new IllegalArgumentException("Missing Redis command argument: " + name);
        }
        return args.get(index);
    }

    private String[] tail(List<String> args, int start) {
        if (args.size() <= start) {
            throw new IllegalArgumentException("Missing Redis command arguments");
        }
        return args.subList(start, args.size()).toArray(new String[0]);
    }

    private Map<String, String> fieldValueMap(List<String> args, int start) {
        if (args.size() <= start) {
            throw new IllegalArgumentException("Missing Redis command arguments");
        }
        int pairCount = args.size() - start;
        if (pairCount % 2 != 0) {
            throw new IllegalArgumentException("Redis HSET requires field/value pairs");
        }
        Map<String, String> values = new LinkedHashMap<>(pairCount / 2);
        for (int i = start; i < args.size(); i += 2) {
            values.put(args.get(i), args.get(i + 1));
        }
        return values;
    }

    private int parseInt(List<String> args, int index, String name) {
        return Integer.parseInt(required(args, index, name));
    }

    private long parseLong(List<String> args, int index, String name) {
        return Long.parseLong(required(args, index, name));
    }

    private double parseDouble(List<String> args, int index, String name) {
        return Double.parseDouble(required(args, index, name));
    }
}
