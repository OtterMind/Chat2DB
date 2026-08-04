package ai.chat2db.plugin.sqlite.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Generated-DDL tests for {@link SqliteColumnTypeEnum#buildDataType}.
 * Verifies that precision is preserved when scale (decimalDigits) is null.
 */
class SqliteColumnTypeEnumTest {

    private TableColumn column(String type, Integer size, Integer digits) {
        TableColumn c = new TableColumn();
        c.setColumnType(type);
        c.setColumnSize(size);
        c.setDecimalDigits(digits);
        return c;
    }

    @Test
    void integerWithPrecisionOnly() {
        // INTEGER(10) — precision set, scale null
        String result = SqliteColumnTypeEnum.INTEGER.buildCreateColumnSql(
                column("INTEGER", 10, null));
        assertEquals(true, result.contains("INTEGER(10)"), () -> "Expected INTEGER(10): " + result);
    }

    @Test
    void realWithPrecisionAndScale() {
        String result = SqliteColumnTypeEnum.REAL.buildCreateColumnSql(
                column("REAL", 10, 2));
        assertEquals(true, result.contains("REAL(10,2)"), () -> "Expected REAL(10,2): " + result);
    }

    @Test
    void textWithBothNull() {
        // Both null — bare TEXT
        String result = SqliteColumnTypeEnum.TEXT.buildCreateColumnSql(
                column("TEXT", null, null));
        assertEquals(true, result.contains("TEXT"), () -> "Expected bare TEXT: " + result);
        assertEquals(false, result.contains("("), () -> "Should not have size suffix: " + result);
    }

    @Test
    void varcharWithSize() {
        // TEXT with size only — the dead-branch fix makes this reachable
        String result = SqliteColumnTypeEnum.TEXT.buildCreateColumnSql(
                column("TEXT", 255, null));
        assertEquals(true, result.contains("TEXT(255)"), () -> "Expected TEXT(255): " + result);
    }
}
