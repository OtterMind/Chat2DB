package ai.chat2db.community.domain.core.impl.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbExplainServiceImplTest {

    @Test
    void acceptsSingleSelectStatementsIncludingCommentsAndCtes() {
        assertTrue(DbExplainServiceImpl.isSingleSelectStatement("SELECT * FROM orders"));
        assertTrue(DbExplainServiceImpl.isSingleSelectStatement("/* dashboard query */ SELECT * FROM orders"));
        assertTrue(DbExplainServiceImpl.isSingleSelectStatement("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent"));
    }

    @Test
    void rejectsWritesAndMultipleStatements() {
        assertFalse(DbExplainServiceImpl.isSingleSelectStatement("UPDATE orders SET status = 'done'"));
        assertFalse(DbExplainServiceImpl.isSingleSelectStatement("SELECT * FROM orders; DELETE FROM orders"));
        assertFalse(DbExplainServiceImpl.isSingleSelectStatement("SELECT * FROM orders; SELECT * FROM users"));
    }

    @Test
    void checksMySqlExplainVersionBoundaries() {
        assertFalse(DbExplainServiceImpl.supportsExplainJson("5.6.51"));
        assertTrue(DbExplainServiceImpl.supportsExplainJson("5.7.44"));
        assertFalse(DbExplainServiceImpl.supportsExplainAnalyze("8.0.17"));
        assertTrue(DbExplainServiceImpl.supportsExplainAnalyze("8.0.18"));
        assertTrue(DbExplainServiceImpl.supportsExplainAnalyze("8.0.36"));
    }
}
