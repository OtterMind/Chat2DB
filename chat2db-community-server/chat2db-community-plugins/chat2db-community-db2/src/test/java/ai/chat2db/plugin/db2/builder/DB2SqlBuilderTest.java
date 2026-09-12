package ai.chat2db.plugin.db2.builder;

import ai.chat2db.spi.model.request.PageLimitRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DB2SqlBuilderTest {

    @Test
    void shouldKeepNormalPaginationOutput() {
        String sql = new DB2SqlBuilder().buildPageLimit(request(10, 10));

        assertEquals("SELECT * FROM (SELECT TMP_PAGE.*,ROWNUMBER() OVER() AS CAHT2DB_AUTO_ROW_ID FROM ( \n"
                + "SELECT ID FROM EMPLOYEE\n"
                + " ) AS TMP_PAGE) TMP_PAGE WHERE CAHT2DB_AUTO_ROW_ID BETWEEN 11 AND 20", sql);
    }

    @Test
    void shouldKeepPaginationBoundsBeyondIntegerRange() {
        String sql = new DB2SqlBuilder().buildPageLimit(request(2_147_483_600, 100));

        assertEquals("SELECT * FROM (SELECT TMP_PAGE.*,ROWNUMBER() OVER() AS CAHT2DB_AUTO_ROW_ID FROM ( \n"
                + "SELECT ID FROM EMPLOYEE\n"
                + " ) AS TMP_PAGE) TMP_PAGE WHERE CAHT2DB_AUTO_ROW_ID BETWEEN 2147483601 AND 2147483700",
                sql);
    }

    private static PageLimitRequest request(int offset, int pageSize) {
        return PageLimitRequest.builder()
                .sql("SELECT ID FROM EMPLOYEE")
                .offset(offset)
                .pageNo(2)
                .pageSize(pageSize)
                .build();
    }
}
