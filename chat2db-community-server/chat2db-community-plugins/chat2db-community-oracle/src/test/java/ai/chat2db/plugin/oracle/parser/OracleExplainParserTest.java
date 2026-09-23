package ai.chat2db.plugin.oracle.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleExplainParserTest {

    @Test
    void shouldExtractExplainedStatement() {
        assertEquals("select * from mt_material_lot_0 where TENANT_ID = 0",
                OracleExplainParser.extractExplainedSql(
                        "EXPLAIN PLAN FOR select * from mt_material_lot_0 where TENANT_ID = 0"));
    }

    @Test
    void shouldIgnoreKeywordCase() {
        assertEquals("select 1 from dual",
                OracleExplainParser.extractExplainedSql("explain plan for select 1 from dual"));
    }

    @Test
    void shouldExtractExplainedStatementWhenStatementIdIsSet() {
        assertEquals("select 1 from dual",
                OracleExplainParser.extractExplainedSql(
                        "EXPLAIN PLAN SET STATEMENT_ID = 'x1' FOR select 1 from dual"));
    }

    @Test
    void shouldNotTreatStatementIdValueAsSeparator() {
        assertEquals("select 1 from dual",
                OracleExplainParser.extractExplainedSql(
                        "EXPLAIN PLAN SET STATEMENT_ID = 'FOR' FOR select 1 from dual"));
    }

    @Test
    void shouldKeepForUpdateInsideTheExplainedStatement() {
        assertEquals("select * from t where a = 1 for update",
                OracleExplainParser.extractExplainedSql(
                        "EXPLAIN PLAN FOR select * from t where a = 1 for update"));
    }

    @Test
    void shouldStripTrailingStatementDelimiter() {
        assertEquals("select 1 from dual",
                OracleExplainParser.extractExplainedSql("EXPLAIN PLAN FOR select 1 from dual;"));
    }

    @Test
    void shouldIgnoreLeadingComments() {
        assertEquals("select 1 from dual",
                OracleExplainParser.extractExplainedSql("-- plan\nEXPLAIN PLAN FOR select 1 from dual"));
        assertEquals("select * from t",
                OracleExplainParser.extractExplainedSql("/* plan */ EXPLAIN PLAN FOR select * from t"));
    }

    @Test
    void shouldExplainDmlStatements() {
        assertEquals("delete from t where a = 1",
                OracleExplainParser.extractExplainedSql("EXPLAIN PLAN FOR delete from t where a = 1"));
        assertEquals("update t set a = 1",
                OracleExplainParser.extractExplainedSql("EXPLAIN PLAN FOR update t set a = 1"));
    }

    @Test
    void shouldIgnoreExplainIntoCustomPlanTable() {
        assertNull(OracleExplainParser.extractExplainedSql("EXPLAIN PLAN INTO plan_tab FOR select 1 from dual"));
    }

    @Test
    void shouldRecognizeExplainPlanCommands() {
        assertTrue(OracleExplainParser.isExplainPlan("EXPLAIN PLAN FOR select 1 from dual"));
        assertTrue(OracleExplainParser.isExplainPlan("explain plan for select 1 from dual"));
        assertTrue(OracleExplainParser.isExplainPlan("EXPLAIN PLAN INTO plan_tab FOR select 1 from dual"));
        assertTrue(OracleExplainParser.isExplainPlan("EXPLAIN PLAN"));
        assertFalse(OracleExplainParser.isExplainPlan("select 1 from dual"));
        assertFalse(OracleExplainParser.isExplainPlan(null));
    }

    @Test
    void shouldIgnoreOrdinaryStatements() {
        assertNull(OracleExplainParser.extractExplainedSql("select * from t"));
        assertNull(OracleExplainParser.extractExplainedSql("delete from t"));
        assertNull(OracleExplainParser.extractExplainedSql(""));
        assertNull(OracleExplainParser.extractExplainedSql(null));
    }

    @Test
    void shouldIgnoreExplainWithoutExplainedStatement() {
        assertNull(OracleExplainParser.extractExplainedSql("EXPLAIN PLAN FOR"));
        assertNull(OracleExplainParser.extractExplainedSql("EXPLAIN PLAN"));
    }
}
