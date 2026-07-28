package ai.chat2db.plugin.xugudb.parser;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.plugin.oracle.parser.OracleSqlParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for plugin:xugudb-3: XUGUDB is an Oracle-dialect plugin
 * (dbms_metadata, DUAL, ALL_* views, IDENTITY), so its SQL parser must use the
 * Oracle grammar like the sibling DM/SUNDB plugins, not the MySQL grammar.
 */
class XUGUDBSqlParserTest {

    @Test
    void extendsOracleSqlParser() {
        assertTrue(OracleSqlParser.class.isAssignableFrom(XUGUDBSqlParser.class),
                "XUGUDBSqlParser must extend OracleSqlParser (Oracle dialect), not MysqlSqlParser");
    }

    @Test
    void splitsPlSqlTriggerBlockAsSingleStatement() {
        XUGUDBSqlParser parser = new XUGUDBSqlParser();
        String sql = "CREATE OR REPLACE TRIGGER trg_bi BEFORE INSERT ON t FOR EACH ROW\n"
                + "BEGIN\n"
                + "  :NEW.id := 1;\n"
                + "END;\n"
                + "/\n"
                + "SELECT 1 FROM DUAL;";
        List<Statement> statements = parser.parserSqlScript(sql);
        assertEquals(2, statements.size(),
                "trigger BEGIN...END block must be one statement, followed by the SELECT");
        String triggerSql = statements.get(0).getOriginalSql();
        assertTrue(triggerSql.contains("BEGIN"), "trigger statement must keep its body");
        assertTrue(triggerSql.contains("END"), "trigger statement must keep its body");
        assertTrue(statements.get(1).getOriginalSql().contains("DUAL"));
    }

    @Test
    void splitsPlainStatements() {
        XUGUDBSqlParser parser = new XUGUDBSqlParser();
        List<Statement> statements = parser.parserSqlScript(
                "SELECT 1 FROM DUAL;\nSELECT 2 FROM DUAL;");
        assertEquals(2, statements.size());
    }
}
