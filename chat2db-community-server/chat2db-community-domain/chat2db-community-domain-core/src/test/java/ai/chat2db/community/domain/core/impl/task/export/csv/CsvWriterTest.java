package ai.chat2db.community.domain.core.impl.task.export.csv;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte level checks for the CSV layout that replaced EasyExcel. The expected bytes are the bytes
 * EasyExcel produced through {@code CSVFormat.DEFAULT}: UTF-8 BOM, comma delimiter, CRLF records,
 * minimal quoting with doubled inner quotes, and empty fields for null values.
 * {@code CsvWriterEasyExcelCompatibilityTest} verifies these expectations against EasyExcel itself.
 */
class CsvWriterTest {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Test
    void writesPlainAndChineseValuesAsUtf8() throws IOException {
        byte[] bytes = write(true, List.of("id", "名称"), List.of(
                List.of("1", "alpha"),
                List.of("2", "中文")));

        assertArrayEquals(csvBytes(
                "id,", "名称", "\r\n",
                "1,alpha", "\r\n",
                "2,", "中文", "\r\n"), bytes);
    }

    @Test
    void quotesDelimiterQuoteAndLineBreakFields() throws IOException {
        byte[] bytes = write(false, List.of(), List.of(
                List.of("a,b", "say \"hi\"", "line1\nline2", "carriage\rreturn")));

        assertArrayEquals(csvBytes(
                "\"a,b\",\"say \"\"hi\"\"\",\"line1\nline2\",\"carriage\rreturn\"", "\r\n"), bytes);
    }

    @Test
    void quotesFieldsWithLeadingOrTrailingWhitespace() throws IOException {
        byte[] bytes = write(false, List.of(), List.of(
                List.of(" leading", "trailing ", "plain")));

        assertArrayEquals(csvBytes("\" leading\",\"trailing \",plain", "\r\n"), bytes);
    }

    @Test
    void writesEmptyFieldsForEmptyStringsAndNulls() throws IOException {
        byte[] bytes = write(false, List.of(), List.of(
                List.of("", "x"),
                List.of("x", ""),
                Arrays.asList("", null),
                Arrays.asList(null, ""),
                Arrays.asList(null, null)));

        assertArrayEquals(csvBytes(
                "\"\",x", "\r\n",
                "x,", "\r\n",
                "\"\",", "\r\n",
                ",", "\r\n",
                ",", "\r\n"), bytes);
    }

    @Test
    void writesTheByteOrderMarkFirst() throws IOException {
        byte[] bytes = write(false, List.of(), List.of(List.of("x")));

        assertArrayEquals(UTF8_BOM, Arrays.copyOf(bytes, UTF8_BOM.length));
    }

    @Test
    void writesTheHeaderOnlyWhenItIsRequested() throws IOException {
        List<List<String>> rows = List.of(List.of("1", "alpha"));

        assertArrayEquals(csvBytes("id,name", "\r\n", "1,alpha", "\r\n"),
                write(true, List.of("id", "name"), rows));
        assertArrayEquals(csvBytes("1,alpha", "\r\n"),
                write(false, List.of("id", "name"), rows));
    }

    @Test
    void writesTheHeaderWithoutAnyRow() throws IOException {
        assertArrayEquals(csvBytes("id,name", "\r\n"),
                write(true, List.of("id", "name"), List.of()));
        assertArrayEquals(UTF8_BOM, write(false, List.of("id", "name"), List.of()));
    }

    @Test
    void keepsTheLegacyBytesWhenTheExplicitDefaultsAreUsed() throws IOException {
        List<String> header = List.of("id", "名称");
        List<List<String>> rows = List.of(List.of("1", "名字"), List.of("a,b", "say \"hi\""));

        assertArrayEquals(write(true, header, rows), writeWith(StandardCharsets.UTF_8, ',', header, rows));
    }

    @Test
    void writesTheRequestedDelimiterAndCharacterSet() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CsvWriter writer = new CsvWriter(output, Charset.forName("GBK"), ';')) {
            writer.writeHeader(List.of("id", "名称"), true);
            writer.writeRow(List.of("1", "值;内"));
        }

        // No byte order mark for a non-UTF-8 file: it would be read as mojibake by a GBK reader.
        assertEquals("id;名称\r\n1;\"值;内\"\r\n", output.toString(Charset.forName("GBK")));
    }

    private static byte[] writeWith(Charset charset, char delimiter, List<String> header, List<List<String>> rows)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CsvWriter writer = new CsvWriter(output, charset, delimiter)) {
            writer.writeHeader(header, true);
            for (List<String> row : rows) {
                writer.writeRow(row);
            }
        }
        return output.toByteArray();
    }

    private static byte[] write(boolean containsHeader, List<String> header, List<List<String>> rows)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CsvWriter writer = new CsvWriter(output)) {
            writer.writeHeader(header, containsHeader);
            for (List<String> row : rows) {
                writer.writeRow(row);
            }
        }
        return output.toByteArray();
    }

    private static byte[] csvBytes(String... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(UTF8_BOM, 0, UTF8_BOM.length);
        for (String part : parts) {
            byte[] encoded = part.getBytes(StandardCharsets.UTF_8);
            output.write(encoded, 0, encoded.length);
        }
        return output.toByteArray();
    }
}
