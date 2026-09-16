package ai.chat2db.spi.util;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import com.alibaba.druid.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlUtilsEditableResultTest {

    @Test
    void resolvesOracleRownumQueryWithCommentsToItsPhysicalTable() {
        assertEditable("""
                SELECT *
                FROM (
                    SELECT * FROM APP.EVENT_LOG
                    -- WHERE METHOD_NAME = 'rollbackTransaction'
                    -- AND CLIENT_IP = '非Web请求'
                    ORDER BY CREATE_TIME DESC
                )
                WHERE ROWNUM <= 100;
                """, DbType.oracle, "APP.EVENT_LOG");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "mysql | SELECT * FROM (SELECT * FROM `app`.`event_log` ORDER BY created_at DESC LIMIT 100) t | `app`.`event_log`",
            "postgresql | SELECT * FROM (SELECT * FROM \"app\".\"EventLog\" ORDER BY created_at DESC LIMIT 100) t | \"app\".\"EventLog\"",
            "sqlserver | SELECT * FROM (SELECT TOP 100 * FROM [app].[dbo].[event_log] ORDER BY created_at DESC) t | [app].[dbo].[event_log]",
            "oracle | SELECT * FROM (SELECT * FROM \"App\".\"EventLog\" ORDER BY CREATE_TIME DESC) WHERE ROWNUM <= 100 | \"App\".\"EventLog\"",
            "postgresql | SELECT \"Log\".* FROM (SELECT \"e\".* FROM \"app\".\"EventLog\" \"e\") \"Log\" | \"app\".\"EventLog\"",
            "oracle | SELECT t.* FROM (SELECT e.* FROM APP.EVENT_LOG e ORDER BY CREATE_TIME DESC) t WHERE ROWNUM <= 100 | APP.EVENT_LOG"
    })
    void resolvesPassthroughQueriesAcrossDialects(DbType dbType, String sql, String tableName) {
        assertEditable(sql, dbType, tableName);
    }

    @Test
    void followsMultipleFromLevelsWithoutChoosingTablesFromFilters() {
        assertEditable("""
                SELECT * FROM (
                    SELECT t.* FROM (
                        SELECT e.* FROM APP.EVENT_LOG e
                        WHERE EXISTS (SELECT 1 FROM APP.FILTERS f WHERE f.ID = e.ID)
                        ORDER BY CREATE_TIME DESC
                    ) t WHERE ROWNUM <= 100
                ) WHERE ROWNUM <= 10
                """, DbType.oracle, "APP.EVENT_LOG");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM APP.EVENT_LOG e JOIN APP.OTHER_LOG o ON e.ID = o.ID",
            "SELECT * FROM APP.EVENT_LOG, APP.OTHER_LOG",
            "SELECT DISTINCT * FROM APP.EVENT_LOG",
            "SELECT COUNT(*) FROM APP.EVENT_LOG",
            "SELECT SUM(ID) FROM APP.EVENT_LOG",
            "SELECT ID FROM APP.EVENT_LOG GROUP BY ID",
            "SELECT ID AS OTHER_ID FROM APP.EVENT_LOG",
            "SELECT ID + 1 AS ID FROM APP.EVENT_LOG",
            "SELECT *, ROW_NUMBER() OVER (ORDER BY ID) AS RN FROM APP.EVENT_LOG",
            "SELECT * FROM APP.EVENT_LOG UNION ALL SELECT * FROM APP.OTHER_LOG",
            "SELECT * FROM APP.EVENT_LOG START WITH ID = 1 CONNECT BY PRIOR ID = PARENT_ID",
            "WITH logs AS (SELECT * FROM APP.EVENT_LOG) SELECT * FROM logs",
            "SELECT * FROM (SELECT * FROM APP.EVENT_LOG) t(OTHER_ID, OTHER_TIME)"
    })
    void keepsNonPassthroughInnerQueriesReadOnlyAtEveryDepth(String innerSql) {
        assertReadOnly("SELECT * FROM (" + innerSql + ") t", DbType.oracle);
        assertReadOnly("SELECT * FROM (SELECT * FROM (" + innerSql + ") t) u", DbType.oracle);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT DISTINCT * FROM (SELECT * FROM APP.EVENT_LOG) t",
            "SELECT ID AS OTHER_ID FROM (SELECT * FROM APP.EVENT_LOG) t",
            "SELECT ID + 1 FROM (SELECT * FROM APP.EVENT_LOG) t",
            "SELECT * FROM (SELECT * FROM APP.EVENT_LOG) t JOIN APP.OTHER_LOG o ON t.ID = o.ID",
            "WITH logs AS (SELECT * FROM APP.EVENT_LOG) SELECT * FROM (SELECT * FROM logs) t",
            "SELECT * FROM (SELECT * FROM APP.EVENT_LOG) t(OTHER_ID, OTHER_TIME)",
            "SELECT * FROM (SELECT * FROM APP.EVENT_LOG) t PIVOT (SUM(ID) FOR STATUS IN ('OK'))"
    })
    void keepsNonPassthroughOuterQueriesReadOnly(String sql) {
        assertReadOnly(sql, DbType.oracle);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM (SELECT (payload).* FROM app.event_log) t",
            "SELECT (payload).* FROM (SELECT * FROM app.event_log) t"
    })
    void keepsCompositeValueExpansionReadOnly(String sql) {
        assertReadOnly(sql, DbType.postgresql);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "mysql | SELECT id, name FROM `app`.`users` | `app`.`users`",
            "postgresql | SELECT * FROM \"app\".\"Users\" | \"app\".\"Users\"",
            "sqlserver | SELECT TOP 10 * FROM [app].[dbo].[users] | [app].[dbo].[users]",
            "oracle | SELECT * FROM APP.USERS WHERE ROWNUM <= 10 | APP.USERS"
    })
    void preservesExistingDirectTableQueries(DbType dbType, String sql, String tableName) {
        assertEditable(sql, dbType, tableName);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(*) FROM APP.USERS",
            "SELECT ID AS OTHER_ID FROM APP.USERS",
            "SELECT * FROM APP.USERS u JOIN APP.OTHER_USERS o ON u.ID = o.ID"
    })
    void preservesExistingReadOnlyQueries(String sql) {
        assertReadOnly(sql, DbType.oracle);
    }

    private static void assertEditable(String sql, DbType dbType, String tableName) {
        ExecuteResponse response = new ExecuteResponse();
        SqlUtils.buildCanEditResult(sql, dbType, response);
        assertTrue(response.isCanEdit(), sql);
        assertEquals(tableName, response.getTableName());
    }

    private static void assertReadOnly(String sql, DbType dbType) {
        ExecuteResponse response = new ExecuteResponse();
        SqlUtils.buildCanEditResult(sql, dbType, response);
        assertFalse(response.isCanEdit(), sql);
        assertNull(response.getTableName(), sql);
    }
}
