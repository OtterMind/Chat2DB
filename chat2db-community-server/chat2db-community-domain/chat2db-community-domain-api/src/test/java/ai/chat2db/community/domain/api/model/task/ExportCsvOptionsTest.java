package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parsing and validation of the CSV output options of an export task.
 */
class ExportCsvOptionsTest {

    @Test
    void keepsTheHistoricalDefaultsWhenNothingIsRequested() {
        ExportCsvOptions options = ExportCsvOptions.of(null, null);

        assertEquals("UTF-8", options.getEncoding());
        assertEquals(",", options.getDelimiter());
        assertEquals(',', options.delimiterChar());
        assertEquals(StandardCharsets.UTF_8, options.charset());
        assertEquals("UTF-8", ExportCsvOptions.defaults().getEncoding());
    }

    @Test
    void normalizesTheRequestedEncodingAndDelimiter() {
        ExportCsvOptions options = ExportCsvOptions.of("gbk", ";");

        assertEquals("GBK", options.getEncoding());
        assertEquals(";", options.getDelimiter());
        assertEquals(';', options.delimiterChar());
        assertEquals("GBK", options.charset().name());
        assertEquals("GBK", options.toMap().get("encoding"));
        assertEquals(";", options.toMap().get("delimiter"));
        // A blank delimiter keeps the comma and a tab is accepted as a single character.
        assertEquals(",", ExportCsvOptions.of(null, "").getDelimiter());
        assertEquals('\t', ExportCsvOptions.of("UTF-8", "\t").delimiterChar());
    }

    @Test
    void rejectsAnUnsupportedEncodingWithItsOwnErrorCode() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> ExportCsvOptions.of("NO-SUCH-CHARSET", null));

        assertEquals("task.export.encoding.invalid", failure.getCode());
        assertEquals("NO-SUCH-CHARSET", failure.getArgs()[0]);
    }

    @Test
    void rejectsDelimitersThatCannotBeWritten() {
        assertEquals("task.export.delimiter.invalid",
                assertThrows(BusinessException.class, () -> ExportCsvOptions.of(null, ";;")).getCode());
        assertEquals("task.export.delimiter.invalid",
                assertThrows(BusinessException.class, () -> ExportCsvOptions.of(null, "\n")).getCode());
        assertEquals("task.export.delimiter.invalid",
                assertThrows(BusinessException.class, () -> ExportCsvOptions.of(null, "\r")).getCode());
        assertEquals("task.export.delimiter.invalid",
                assertThrows(BusinessException.class, () -> ExportCsvOptions.of(null, "\"")).getCode());
    }
}
