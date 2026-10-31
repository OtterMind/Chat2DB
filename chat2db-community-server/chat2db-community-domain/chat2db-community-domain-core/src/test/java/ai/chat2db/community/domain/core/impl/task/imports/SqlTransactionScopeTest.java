package ai.chat2db.community.domain.core.impl.task.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the script transaction scope that stops insert batches from committing inside a
 * {@code BEGIN} ... {@code ROLLBACK} script (see the import path in {@link SqlScriptExecutor}).
 */
class SqlTransactionScopeTest {
    @Test
    void proceduralBlocksDoNotChangeTheScriptTransactionScope() {
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("BEGIN NULL; END;"));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("BEGIN TRY SELECT 1; END TRY"));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("END IF;"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("BEGIN TRANSACTION named_transaction"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("BEGIN ISOLATION LEVEL SERIALIZABLE READ ONLY"));
    }

    @Test
    void productionOracleParserKeepsAnAnonymousBlockOutsideTransactionScope() {
        var statements = new ai.chat2db.plugin.oracle.parser.OracleSqlParser()
                .parserSqlScript("BEGIN\n NULL;\nEND;\n/\n");
        assertEquals(1, statements.size());
        assertEquals(SqlTransactionScope.Effect.NONE,
                SqlTransactionScope.effectOf(statements.get(0).getSql()));
    }

    @Test
    void savepointRollbackKeepsTheScriptTransactionOpen() {
        SqlTransactionScope scope = new SqlTransactionScope();
        scope.observe("BEGIN");
        scope.observe("ROLLBACK TO SAVEPOINT saved");
        assertTrue(scope.isOpen());
        scope.observe("ROLLBACK WORK TO saved");
        assertTrue(scope.isOpen());
        scope.observe("ROLLBACK TRANSACTION TO SAVEPOINT saved");
        assertTrue(scope.isOpen());
    }

    @Test
    void chainedCommitAndRollbackKeepTheNextTransactionOpen() {
        SqlTransactionScope scope = new SqlTransactionScope();
        scope.observe("BEGIN");
        scope.observe("COMMIT AND CHAIN");
        assertTrue(scope.isOpen());
        scope.observe("ROLLBACK WORK AND CHAIN");
        assertTrue(scope.isOpen());
        scope.observe("COMMIT AND NO CHAIN");
        assertFalse(scope.isOpen());
    }

    @Test
    void postgresEndAndAbortAliasesCloseTheCurrentTransaction() {
        SqlTransactionScope scope = new SqlTransactionScope();
        scope.observe("BEGIN");
        scope.observe("END");
        assertFalse(scope.isOpen());
        scope.observe("BEGIN");
        scope.observe("ABORT");
        assertFalse(scope.isOpen());
    }

    @Test
    void unrelatedStartCommandIsNotATransaction() {
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("START REPLICA"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("START TRANSACTION"));
    }

    @Test
    void classifiesTransactionControlStatementsRegardlessOfCaseAndNoise() {
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("BEGIN"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("begin transaction"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("START TRANSACTION"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("  \n\tbegin  ;"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("-- open it\nBEGIN;"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("/* header */ begin work"));
        assertEquals(SqlTransactionScope.Effect.COMMIT, SqlTransactionScope.effectOf("COMMIT;"));
        assertEquals(SqlTransactionScope.Effect.ROLLBACK, SqlTransactionScope.effectOf("rollback;"));
    }

    @Test
    void ordinaryStatementsAndEmptyInputNeverOpenOrCloseATransaction() {
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("INSERT INTO t(id) VALUES (1)"));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("beginner_report(id)"));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("(SELECT 1)"));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf(""));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf(null));
    }

    @Test
    void scopeOpensOnBeginAndClosesOnlyOnCommitOrRollback() {
        SqlTransactionScope scope = new SqlTransactionScope();
        assertFalse(scope.isOpen());

        scope.observe("BEGIN");
        assertTrue(scope.isOpen(), "BEGIN must open the script transaction");

        scope.observe("INSERT INTO t(id) VALUES (1)");
        assertTrue(scope.isOpen(), "a data statement must not close the script transaction");

        scope.observe("ROLLBACK;");
        assertFalse(scope.isOpen(), "ROLLBACK must close the script transaction");

        scope.observe("START TRANSACTION");
        assertTrue(scope.isOpen());
        scope.observe("COMMIT");
        assertFalse(scope.isOpen());
    }

    /**
     * The batch executor must stop managing transactions exactly while the script owns one;
     * otherwise a script ROLLBACK cannot discard the rows its INSERT batches already wrote.
     */
    @Test
    void importExecutorStopsManagingTransactionsWhileTheScriptOwnsOne() {
        SqlScriptExecutor executor = new SqlScriptExecutor(null);
        assertTrue(executor.managesOwnTransaction(), "a script without BEGIN keeps self-managed batches");

        executor.observeExecutedStatement("BEGIN");
        assertFalse(executor.managesOwnTransaction(), "a script BEGIN must suspend batch commits");

        executor.observeExecutedStatement("INSERT INTO t(id) VALUES (1)");
        assertFalse(executor.managesOwnTransaction(), "INSERTs must not re-enable batch commits");

        executor.observeExecutedStatement("ROLLBACK");
        assertTrue(executor.managesOwnTransaction(), "after ROLLBACK the script no longer owns a transaction");
    }
}
