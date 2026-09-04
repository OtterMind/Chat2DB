package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionOperation;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlTransactionPolicyTest {

    private IPlugin previousMysqlPlugin;

    @BeforeEach
    void registerMysqlPlugin() {
        previousMysqlPlugin = Chat2DBContext.PLUGIN_MAP.put("MYSQL", new MysqlPlugin());
    }

    @AfterEach
    void clearContext() {
        Chat2DBContext.removeContext();
        if (previousMysqlPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove("MYSQL");
        } else {
            Chat2DBContext.PLUGIN_MAP.put("MYSQL", previousMysqlPlugin);
        }
    }

    @Test
    void blocksImplicitCommitStatementsOnBoundMysqlTransactions() {
        bindMysql(true);

        assertThrows(BusinessException.class, () -> Chat2DBContext.beforeExecute(plan(
                "INSERT INTO tx_innodb(val) VALUES ('pending'); CREATE TABLE marker(id INT)")));
    }

    @Test
    void blocksAutocommitEnableVariantsOnBoundMysqlTransactions() {
        bindMysql(true);

        assertBlocked("SET autocommit=1");
        assertBlocked("SET SESSION autocommit = ON");
        assertBlocked("SET @@session.autocommit = 1");
        assertBlocked("SET /* keep parsing */ autocommit = 1");
    }

    @Test
    void blocksDynamicSqlAndExecutableCommentsOnBoundMysqlTransactions() {
        bindMysql(true);

        assertBlocked("PREPARE ddl FROM 'CREATE TABLE marker(id INT)'");
        assertBlocked("EXECUTE ddl");
        assertBlocked("CALL commit_pending_work()");
        assertBlocked("{ CALL commit_pending_work() }");
        assertBlocked("/*!80000 CREATE TABLE marker(id INT) */");
    }

    @Test
    void blocksTransactionEndingStatementsButAllowsSavepointRollback() {
        bindMysql(true);

        assertBlocked("COMMIT");
        assertBlocked("ROLLBACK");
        assertBlocked("ROLLBACK WORK");
        assertDoesNotThrow(() -> Chat2DBContext.beforeExecute(plan("ROLLBACK TO SAVEPOINT sp1")));
    }

    @Test
    void allowsTransactionalStatementsAndUnboundConnections() {
        bindMysql(true);
        assertDoesNotThrow(() -> Chat2DBContext.beforeExecute(plan("INSERT INTO tx_innodb(val) VALUES ('pending')")));

        bindMysql(false);
        assertDoesNotThrow(() -> Chat2DBContext.beforeExecute(plan("CREATE TABLE marker(id INT)")));
    }

    private static void bindMysql(boolean consoleOwn) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("MYSQL");
        connectInfo.setConsoleOwn(consoleOwn);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    private static void assertBlocked(String sql) {
        assertThrows(BusinessException.class, () -> Chat2DBContext.beforeExecute(plan(sql)));
    }

    private static SqlExecutionPlan plan(String sql) {
        SqlExecutionContext context = new SqlExecutionContext(
                1L, "MYSQL", "c2d_tx_test", null, null, sql,
                SqlExecutionOperation.EXECUTE, null, null
        );
        return new SqlExecutionPlan(context, sql, null, "test-execution");
    }
}
