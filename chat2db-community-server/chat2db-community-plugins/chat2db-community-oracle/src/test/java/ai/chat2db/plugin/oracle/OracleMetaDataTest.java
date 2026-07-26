package ai.chat2db.plugin.oracle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OracleMetaDataTest {

    @Test
    void buildTablesSqlEscapesSchemaAndTableFiltersAsLiterals() {
        String sql = OracleMetaData.buildTablesSql("SCOTT' OR '1'='1", "O'Brien' OR '1'='1");

        assertEquals("SELECT A.OWNER, A.TABLE_NAME, B.COMMENTS FROM ALL_TABLES A "
                        + "LEFT JOIN ALL_TAB_COMMENTS B ON  A.OWNER = B.OWNER  AND A.TABLE_NAME = B.TABLE_NAME\n"
                        + "where A.OWNER = 'SCOTT'' OR ''1''=''1'  "
                        + "and A.TABLE_NAME = 'O''Brien'' OR ''1''=''1'",
                sql);
        assertFalse(sql.contains("A.TABLE_NAME = 'O'Brien'"));
    }

    @Test
    void appendRoutineSourceTextPreservesOracleLineTerminators() {
        StringBuilder builder = new StringBuilder("CREATE OR REPLACE ");

        OracleMetaData.appendRoutineSourceText(builder, "procedure p_test(\n");
        OracleMetaData.appendRoutineSourceText(builder, "  p_id in number\n");
        OracleMetaData.appendRoutineSourceText(builder, ")\n");

        assertEquals("CREATE OR REPLACE procedure p_test(\n  p_id in number\n)\n", builder.toString());
    }

    @Test
    void appendRoutineSourceTextAddsSeparatorWhenMissing() {
        StringBuilder builder = new StringBuilder("CREATE OR REPLACE ");

        OracleMetaData.appendRoutineSourceText(builder, "procedure p_test(");
        OracleMetaData.appendRoutineSourceText(builder, "  p_id in number");
        OracleMetaData.appendRoutineSourceText(builder, ")");

        assertEquals("CREATE OR REPLACE procedure p_test(\n  p_id in number\n)\n", builder.toString());
    }

    @Test
    void appendRoutineSourceTextPreservesExplicitBlankLines() {
        StringBuilder builder = new StringBuilder();

        OracleMetaData.appendRoutineSourceText(builder, "  update products\n");
        OracleMetaData.appendRoutineSourceText(builder, "\n");
        OracleMetaData.appendRoutineSourceText(builder, "  if sql%rowcount = 0 then\n");

        assertEquals("  update products\n\n  if sql%rowcount = 0 then\n", builder.toString());
    }
}
