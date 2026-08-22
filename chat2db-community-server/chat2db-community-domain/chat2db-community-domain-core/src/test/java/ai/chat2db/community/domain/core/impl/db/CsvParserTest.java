package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

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
}
