package ai.chat2db.plugin.postgresql;

import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PostgreSQLCommandExecutorTest {

    @Test
    void metadataUsesThePostgreSqlExecutor() {
        assertSame(PostgreSQLCommandExecutor.INSTANCE, new PostgreSQLMetaData().getCommandExecutor());
    }

    @Test
    void textAndNullAreBoundUntypedSoTheServerInfersTheirType() throws Exception {
        List<String> calls = new ArrayList<>();
        PreparedStatement statement = recordingStatement(calls);

        PostgreSQLCommandExecutor.INSTANCE.bindParameterValue(statement, 1, SqlParameterValue.string("2024-01-31"));
        PostgreSQLCommandExecutor.INSTANCE.bindParameterValue(statement, 2, SqlParameterValue.nullValue());
        PostgreSQLCommandExecutor.INSTANCE.bindParameterValue(statement, 3, SqlParameterValue.string(""));

        assertEquals(List.of(
                "setObject(1, 2024-01-31, " + Types.OTHER + ")",
                "setNull(2, " + Types.OTHER + ")",
                "setObject(3, , " + Types.OTHER + ")"), calls);
    }

    @Test
    void numbersAndBooleansKeepTheirTypedBindCalls() throws Exception {
        List<String> calls = new ArrayList<>();
        PreparedStatement statement = recordingStatement(calls);

        PostgreSQLCommandExecutor.INSTANCE.bindParameterValue(statement, 1, SqlParameterValue.number("42"));
        PostgreSQLCommandExecutor.INSTANCE.bindParameterValue(statement, 2, SqlParameterValue.bool("true"));

        assertEquals(List.of("setBigDecimal(1, 42)", "setBoolean(2, true)"), calls);
    }

    private static PreparedStatement recordingStatement(List<String> calls) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("set")) {
                        StringBuilder call = new StringBuilder(method.getName()).append('(');
                        for (int i = 0; i < args.length; i++) {
                            call.append(i == 0 ? "" : ", ").append(args[i]);
                        }
                        calls.add(call.append(')').toString());
                    }
                    return null;
                });
    }
}
