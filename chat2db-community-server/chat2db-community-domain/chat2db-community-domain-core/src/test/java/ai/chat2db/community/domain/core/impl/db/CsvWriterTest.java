package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.task.CsvOptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvWriterTest {

    @Test
    void writesConfiguredDelimiterEscapingNewlineAndNullSafeEmptyStrings() throws Exception {
        CsvOptions options = CsvOptions.builder()
                .encoding("UTF-8")
                .delimiter("|")
                .quote("\"")
                .escape("\\")
                .newline("CRLF")
                .hasHeader(true)
                .emptyAsNull(true)
                .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CsvWriter writer = new CsvWriter(options, output);

        writer.writeRow(List.of("name", "note", "empty", "missing"));
        writer.writeRow(Arrays.asList("Ada", "hello \"db\"\nagain", "", null));
        writer.close();

        assertEquals("name|note|empty|missing\r\nAda|\"hello \\\"db\\\"\nagain\"|\"\"|\r\n",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void neutralizesSpreadsheetFormulasOnlyWhenWritingCsv() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CsvWriter writer = new CsvWriter(CsvOptions.defaults(), output);

        writer.writeRow(List.of("formula", "literal"));
        writer.writeRow(List.of("=1+1", "C:\\temp\\new"));
        writer.close();

        assertEquals("formula,literal\n'=1+1,C:\\temp\\new\n", output.toString(StandardCharsets.UTF_8));
    }
}
