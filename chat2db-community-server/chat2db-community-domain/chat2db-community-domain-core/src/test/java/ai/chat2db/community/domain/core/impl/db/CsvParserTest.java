package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvParserTest {

    @Test
    void parsesQuotedDelimitersAndEmbeddedNewlines() {
        CsvParser parser = new CsvParser("UTF-8", ";", "\"", "\"", true, true);

        CsvParser.CsvResult result = parser.parse("name;note\r\nAda;\"first; second\nline\"\r\n".getBytes(), 50);

        assertEquals(2, result.rows().size());
        assertEquals("first; second\nline", result.rows().get(1).get(1));
    }

    @Test
    void decodesTheSelectedCharsetAndRejectsUnclosedQuotes() {
        CsvParser gb18030 = new CsvParser("GB18030", ",", "\"", "\"", true, true);
        assertEquals("中文", gb18030.parse("name\n中文\n".getBytes(Charset.forName("GB18030")), 50)
                .rows().get(1).get(0));

        CsvParser utf8 = new CsvParser("UTF-8", ",", "\"", "\"", true, true);
        assertThrows(BusinessException.class, () -> utf8.parse("name\n\"unterminated".getBytes(), 50));
    }

    @Test
    void parsesValidatedFileBytes() {
        CsvParser parser = new CsvParser("UTF-8", ",", "\"", "\"", true, true);

        CsvParser.CsvResult result = parser.parse("name\nAda\n".getBytes(Charset.forName("UTF-8")), 50);

        assertEquals("Ada", result.rows().get(1).get(0));
    }

    @Test
    void rejectsCsvOptionsOutsideTheSupportedImportContract() {
        assertEquals("import.preview.invalidCsvOptions",
                assertThrows(BusinessException.class,
                        () -> new CsvParser("UTF-8", ",,", "\"", "\"", true, true)).getCode());
        assertEquals("import.preview.invalidCsvOptions",
                assertThrows(BusinessException.class,
                        () -> new CsvParser("UTF-8", ",", ",", ",", true, true)).getCode());
        assertEquals("import.preview.invalidCsvOptions",
                assertThrows(BusinessException.class,
                        () -> new CsvParser("UTF-16", ",", "\"", "\"", true, true)).getCode());
    }

    @Test
    void reportsSourceLineForMalformedEncodingAndQuoting() {
        CsvParser parser = new CsvParser("UTF-8", ",", "\"", "\"", true, true);
        byte[] validPrefix = "name\nAda\n".getBytes(StandardCharsets.UTF_8);
        byte[] invalidUtf8 = java.util.Arrays.copyOf(validPrefix, validPrefix.length + 2);
        invalidUtf8[invalidUtf8.length - 2] = (byte) 0xC3;
        invalidUtf8[invalidUtf8.length - 1] = (byte) 0x28;

        BusinessException encoding = assertThrows(BusinessException.class, () -> parser.parse(invalidUtf8, 50));
        assertEquals("import.preview.invalidEncodingLine", encoding.getCode());
        assertEquals(3, encoding.getArgs()[1]);

        BusinessException quote = assertThrows(BusinessException.class,
                () -> parser.parse(new StringReader("name,note\nAda,\"ok\"x\n"), 50));
        assertEquals("import.preview.malformedCsv", quote.getCode());
        assertEquals(2, quote.getArgs()[0]);
    }

    @Test
    void appliesEmptyAsNullConsistentlyForCsvRows() {
        CsvParser emptyAsNull = new CsvParser("UTF-8", ",", "\"", "\"", true, true);
        assertEquals(null, emptyAsNull.parse("name,note\nAda,\n".getBytes(StandardCharsets.UTF_8), 50)
                .rows().get(1).get(1));

        CsvParser emptyAsText = new CsvParser("UTF-8", ",", "\"", "\"", true, false);
        assertEquals("", emptyAsText.parse("name,note\nAda,\n".getBytes(StandardCharsets.UTF_8), 50)
                .rows().get(1).get(1));
    }

    @Test
    void detectsUtfBomAndKeepsQuotedEmptyDistinctFromNull() {
        CsvParser parser = new CsvParser("AUTO", ",", "\"", "\"", true, true);
        byte[] body = "name,note\nAda,\"\"\nGrace,\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(body, 0, bytes, 2, body.length);

        CsvParser.CsvResult result = parser.parse(bytes, 50);

        assertEquals("name", result.rows().get(0).get(0));
        assertEquals("", result.rows().get(1).get(1));
        assertEquals(null, result.rows().get(2).get(1));
    }

    @Test
    void supportsBackslashEscapedQuotesWhenConfigured() {
        CsvParser parser = new CsvParser("UTF-8", ",", "\"", "\\", true, true);

        CsvParser.CsvResult result = parser.parse("name,note\nAda,\"hello \\\"db\\\"\"\n".getBytes(StandardCharsets.UTF_8),
                50);

        assertEquals("hello \"db\"", result.rows().get(1).get(1));
    }

    @Test
    void preservesLiteralBackslashesWhenTheyDoNotEscapeQuoteOrEscape() {
        CsvParser parser = new CsvParser("UTF-8", ",", "\"", "\\", true, true);

        CsvParser.CsvResult result = parser.parse("name,path\nAda,\"C:\\temp\\new\"\n".getBytes(StandardCharsets.UTF_8),
                50);

        assertEquals("C:\\temp\\new", result.rows().get(1).get(1));
    }

    @Test
    void keepsFinalSingleColumnQuotedEmptyRow() {
        CsvParser parser = new CsvParser("UTF-8", ",", "\"", "\"", false, true);

        CsvParser.CsvResult result = parser.parse("\"\"".getBytes(StandardCharsets.UTF_8), 50);

        assertEquals(1, result.rows().size());
        assertEquals("", result.rows().get(0).get(0));
    }
}
