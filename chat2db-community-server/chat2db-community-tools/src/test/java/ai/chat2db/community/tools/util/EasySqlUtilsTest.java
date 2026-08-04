package ai.chat2db.community.tools.util;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link EasySqlUtils#parseTableSchema(String, StringBuilder)}
 * with 5+-part dotted identifiers (e.g. linked-server style names): previously the
 * switch had no default branch, leaving actualTableName null and NPEing on replaceAll.
 */
class EasySqlUtilsTest {

    @Test
    @SuppressWarnings("unchecked")
    void fivePartDottedIdentifierFallsBackToLastSegment() {
        StringBuilder error = new StringBuilder();
        // A genuine 5-part identifier hits the new default branch (4-part srv.db.dbo.t1
        // was already handled by case 4, so it could not prove the fix).
        Map<String, Object> result = EasySqlUtils.parseTableSchema("SELECT * FROM srv.db.dbo.sch.t1", error);
        assertEquals(0, error.length(), "no exception should be recorded");
        List<String> tables = (List<String>) result.get(EasySqlUtils.TABLE_NAME);
        assertTrue(tables.contains("t1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyFinalDottedSegmentIsNotAddedAsATable() {
        StringBuilder error = new StringBuilder();
        Map<String, Object> result = EasySqlUtils.parseTableSchema(
            "SELECT * FROM srv.db.dbo.sch.``", error);
        assertEquals(0, error.length(), "no exception should be recorded");
        List<String> tables = (List<String>) result.get(EasySqlUtils.TABLE_NAME);
        assertTrue(tables.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void threePartIdentifierStillParses() {
        StringBuilder error = new StringBuilder();
        Map<String, Object> result = EasySqlUtils.parseTableSchema("SELECT * FROM db.schema1.t2", error);
        List<String> tables = (List<String>) result.get(EasySqlUtils.TABLE_NAME);
        assertTrue(tables.contains("t2"));
        List<String> schemas = (List<String>) result.get(EasySqlUtils.SCHEMA_NAME);
        assertTrue(schemas.contains("schema1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void plainTableNameStillParses() {
        StringBuilder error = new StringBuilder();
        Map<String, Object> result = EasySqlUtils.parseTableSchema("SELECT * FROM t3", error);
        List<String> tables = (List<String>) result.get(EasySqlUtils.TABLE_NAME);
        assertTrue(tables.contains("t3"));
    }
}
