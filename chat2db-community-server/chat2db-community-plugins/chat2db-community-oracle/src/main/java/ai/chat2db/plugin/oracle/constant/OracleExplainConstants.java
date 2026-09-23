package ai.chat2db.plugin.oracle.constant;

/**
 * SQL fragments and markers used to read an explained plan back from the plan
 * table.
 *
 * <p>{@code EXPLAIN PLAN ... FOR <statement>} writes plan rows into the plan
 * table and returns no result set, so the plan is read back through
 * {@code DBMS_XPLAN} under the statement id the command was written with.
 */
public final class OracleExplainConstants {

    public static final String PLAN_TABLE = "PLAN_TABLE";
    public static final String PLAN_TABLE_PROBE_SQL = "SELECT 1 FROM " + PLAN_TABLE + " WHERE ROWNUM = 1";
    public static final String EXPLAIN_PLAN_SQL =
            "EXPLAIN PLAN SET STATEMENT_ID = '%s' INTO " + PLAN_TABLE + " FOR ";
    public static final String DISPLAY_PLAN_SQL =
            "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('" + PLAN_TABLE + "', ?, 'TYPICAL'))";
    public static final String PLAN_ERROR_PREFIX = "Error:";
    public static final String STATEMENT_ID_PREFIX = "CHAT2DB_";
    public static final int STATEMENT_ID_MAX_LENGTH = 30;
    public static final String EXPLAIN_KEYWORD = "EXPLAIN";
    public static final String PLAN_KEYWORD = "PLAN";
    public static final String FOR_KEYWORD = "FOR";
    public static final String INTO_KEYWORD = "INTO";

    private OracleExplainConstants() {
    }
}
