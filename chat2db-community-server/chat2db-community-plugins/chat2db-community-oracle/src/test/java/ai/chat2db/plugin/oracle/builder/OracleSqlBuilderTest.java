package ai.chat2db.plugin.oracle.builder;

import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.spi.model.request.PageLimitRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSqlBuilderTest {

    @Test
    void shouldLimitFirstPageWithoutExposingSyntheticRowId() {
        OracleSqlBuilder builder = new OracleSqlBuilder();

        String sql = builder.buildPageLimit(PageLimitRequest.builder()
                .sql("SELECT ID, NAME FROM EMPLOYEE")
                .offset(0)
                .pageNo(1)
                .pageSize(10)
                .build());

        assertEquals("SELECT * FROM ( \nSELECT ID, NAME FROM EMPLOYEE\n ) TMP_PAGE WHERE ROWNUM <= 10", sql);
        assertFalse(sql.contains("CAHT2DB_AUTO_ROW_ID"));
    }

    @Test
    void shouldApplyUpperAndLowerBoundsAfterFirstPage() {
        OracleSqlBuilder builder = new OracleSqlBuilder();

        String sql = builder.buildPageLimit(PageLimitRequest.builder()
                .sql("SELECT ID, NAME FROM EMPLOYEE")
                .offset(10)
                .pageNo(2)
                .pageSize(10)
                .build());

        assertEquals("SELECT * FROM (  SELECT TMP_PAGE.*, ROWNUM CAHT2DB_AUTO_ROW_ID FROM ( \n"
                        + "SELECT ID, NAME FROM EMPLOYEE\n"
                        + " ) TMP_PAGE WHERE ROWNUM <= 20 ) WHERE CAHT2DB_AUTO_ROW_ID > 10",
                sql);
    }

    @Test
    void shouldBuildQualifiedAndEscapedViewComment() {
        OracleSqlBuilder builder = new OracleSqlBuilder();
        ModifyView view = view("SA\"LES", "ACTIVE\"USERS", "Owner\\team's active users");

        String sql = builder.buildCreateView(view);

        assertTrue(sql.startsWith("CREATE VIEW \"SA\"\"LES\".\"ACTIVE\"\"USERS\""));
        assertTrue(sql.endsWith(
                "COMMENT ON TABLE \"SA\"\"LES\".\"ACTIVE\"\"USERS\" is 'Owner\\team''s active users';"));
        assertFalse(sql.contains("Owner\\\\team"));
    }

    @Test
    void shouldBuildUnqualifiedViewCommentWhenSchemaIsMissing() {
        OracleSqlBuilder builder = new OracleSqlBuilder();
        ModifyView view = view(null, "ACTIVE_USERS", "Active users");

        String sql = builder.buildCreateView(view);

        assertTrue(sql.endsWith("COMMENT ON TABLE \"ACTIVE_USERS\" is 'Active users';"));
        assertFalse(sql.contains("\"null\""));
    }

    @Test
    void shouldUseRowidSubQueryWhenLimitingSingleRowDeleteAndUpdate() {
        OracleSqlBuilder builder = new OracleSqlBuilder();
        String where = " where \"A\" = 1 and \"B\" = 2";

        assertEquals("DELETE FROM \"T\" where rowid in (select rowid from \"T\"" + where + " and rownum = 1)",
                builder.appendSingleRowLimit("DELETE", "\"T\"", where, "DELETE FROM \"T\"" + where));
        assertEquals("UPDATE \"T\" set \"A\" = 1 where rowid in (select rowid from \"T\"" + where + " and rownum = 1)",
                builder.appendSingleRowLimit("UPDATE", "\"T\"", where, "UPDATE \"T\" set \"A\" = 1" + where));
    }

    private static ModifyView view(String schemaName, String viewName, String comment) {
        ModifyView view = new ModifyView();
        view.setSchemaName(schemaName);
        view.setViewName(viewName);
        view.setViewBody("SELECT ID FROM EMPLOYEE");
        view.setComment(comment);
        return view;
    }
}
