package ai.chat2db.plugin.kingbase;

import ai.chat2db.plugin.postgresql.PostgreSQLCommandExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class KingBaseCommandExecutorTest {

    @Test
    void parametersAreBoundLikePostgreSql() {
        assertSame(PostgreSQLCommandExecutor.INSTANCE, new KingBaseMetaData().getCommandExecutor());
    }
}
