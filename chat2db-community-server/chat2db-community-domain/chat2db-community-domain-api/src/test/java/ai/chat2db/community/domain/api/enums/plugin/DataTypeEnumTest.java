package ai.chat2db.community.domain.api.enums.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for code-review finding core:domain-api-3:
 * quoted branches of getSqlValue must escape like the STRING branch.
 */
class DataTypeEnumTest {

    @Test
    void stringBranchEscapesLiteralDelimitersAndBackslashes() {
        assertEquals("'O''Brien'", DataTypeEnum.STRING.getSqlValue("O'Brien"));
        assertEquals("'a\\\\b'", DataTypeEnum.STRING.getSqlValue("a\\b"));
        assertEquals("'a\"b'", DataTypeEnum.STRING.getSqlValue("a\"b"));
    }

    @Test
    void datetimeBranchEscapesLikeString() {
        assertEquals(DataTypeEnum.STRING.getSqlValue("2024-01-01 '; DROP TABLE t; --"),
                DataTypeEnum.DATETIME.getSqlValue("2024-01-01 '; DROP TABLE t; --"));
        assertEquals("'O''Brien'", DataTypeEnum.DATETIME.getSqlValue("O'Brien"));
    }

    @Test
    void unknownBranchEscapesLikeString() {
        assertEquals(DataTypeEnum.STRING.getSqlValue("x'y\\z"),
                DataTypeEnum.UNKNOWN.getSqlValue("x'y\\z"));
        // getByCode falls back to UNKNOWN for unrecognized types
        assertEquals(DataTypeEnum.UNKNOWN, DataTypeEnum.getByCode("SOME_UNLISTED_TYPE"));
        assertEquals(DataTypeEnum.STRING.getSqlValue("O'Brien"),
                DataTypeEnum.getByCode("SOME_UNLISTED_TYPE").getSqlValue("O'Brien"));
    }

    @Test
    void allQuotedBranchesEscapeLikeString() {
        String value = "O'Brien\\";
        String expected = DataTypeEnum.STRING.getSqlValue(value);
        assertEquals(expected, DataTypeEnum.CONTENT.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.STRUCT.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.DOCUMENT.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.ARRAY.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.OBJECT.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.REFERENCE.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.ROWID.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.ANY.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.UNKNOWN.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.BIT.getSqlValue(value));
        assertEquals(expected, DataTypeEnum.CHAT2DB_ROW_NUMBER.getSqlValue(value));
    }

    @Test
    void nonQuotedBranchesUnchanged() {
        assertEquals("true", DataTypeEnum.BOOLEAN.getSqlValue("true"));
        assertEquals("FALSE", DataTypeEnum.BOOLEAN.getSqlValue("FALSE"));
        assertEquals("42", DataTypeEnum.NUMERIC.getSqlValue("42"));
        assertEquals("''", DataTypeEnum.BINARY.getSqlValue("anything"));
    }
}
