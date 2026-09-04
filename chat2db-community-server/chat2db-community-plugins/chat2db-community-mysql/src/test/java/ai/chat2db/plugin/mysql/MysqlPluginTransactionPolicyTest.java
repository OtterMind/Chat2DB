package ai.chat2db.plugin.mysql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlPluginTransactionPolicyTest {

    private final MysqlPlugin plugin = new MysqlPlugin();

    @Test
    void declaresManualTransactionSupport() {
        assertTrue(plugin.supportsManualTransactions());
    }

    @Test
    void detectsStatementsThatImplicitlyCommit() {
        assertTrue(plugin.isImplicitCommitStatement("CREATE_TABLE", "CREATE TABLE t(id INT)"));
        assertTrue(plugin.isImplicitCommitStatement("ALTER_TABLE", "ALTER TABLE t ADD c INT"));
        assertTrue(plugin.isImplicitCommitStatement("TRUNCATE_TABLE", "TRUNCATE TABLE t"));
        assertTrue(plugin.isImplicitCommitStatement("SET_AUTOCOMMIT", "SET SESSION autocommit = ON"));
        assertTrue(plugin.isImplicitCommitStatement(null, "SET autocommit=1"));
        assertTrue(plugin.isImplicitCommitStatement(null, "SET /* keep parsing */ autocommit=1"));
        assertTrue(plugin.isImplicitCommitStatement("SET_AUTOCOMMIT", "SET @@session.autocommit = ON"));
        assertTrue(plugin.isImplicitCommitStatement("PREPARE", "PREPARE ddl FROM 'CREATE TABLE t(id INT)'"));
        assertTrue(plugin.isImplicitCommitStatement("EXECUTE", "EXECUTE ddl"));
        assertTrue(plugin.isImplicitCommitStatement("CALL", "CALL commit_pending_work()"));
        assertTrue(plugin.isImplicitCommitStatement(null, "{ CALL commit_pending_work() }"));
        assertTrue(plugin.isImplicitCommitStatement(null, "/*!80000 CREATE TABLE t(id INT) */"));
        assertFalse(plugin.isImplicitCommitStatement(null, "SELECT '/*!80000 CREATE TABLE t(id INT) */'"));
    }

    @Test
    void detectsTransactionEndingStatementsWithoutBlockingSavepointRollback() {
        assertTrue(plugin.isImplicitCommitStatement("COMMIT", "COMMIT"));
        assertTrue(plugin.isImplicitCommitStatement("ROLLBACK", "ROLLBACK"));
        assertTrue(plugin.isImplicitCommitStatement("ROLLBACK_WORK", "ROLLBACK WORK"));
        assertFalse(plugin.isImplicitCommitStatement("ROLLBACK", "ROLLBACK TO SAVEPOINT sp1"));
    }

    @Test
    void leavesTransactionalStatementsAlone() {
        assertFalse(plugin.isImplicitCommitStatement("INSERT", "INSERT INTO t VALUES (1)"));
        assertFalse(plugin.isImplicitCommitStatement("SET_AUTOCOMMIT", "SET autocommit = 0"));
        assertFalse(plugin.isImplicitCommitStatement(null, "SELECT 'CREATE TABLE t(id INT)'"));
    }
}
