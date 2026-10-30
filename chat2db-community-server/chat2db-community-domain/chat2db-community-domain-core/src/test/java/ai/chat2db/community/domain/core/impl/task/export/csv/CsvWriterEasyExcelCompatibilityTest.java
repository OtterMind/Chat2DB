package ai.chat2db.community.domain.core.impl.task.export.csv;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.builder.ExcelWriterBuilder;
import com.alibaba.excel.write.metadata.WriteSheet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Proves byte compatibility between {@link CsvWriter} and the EasyExcel CSV path it replaced
 * ({@code ExcelTypeEnum.CSV} plus one {@code ExcelWriter#write(List, WriteSheet)} per row). Every
 * expectation is produced by EasyExcel at test time instead of being hand written, so a change in
 * the byte order mark, record separator, quoting, or null handling fails this test.
 *
 * <p>The only verified behaviour difference is an empty export: EasyExcel fails with
 * {@code ExcelGenerateException: Can not close IO} when no data row is written, while
 * {@link CsvWriter} emits the byte order mark and the header. That failure is locked by
 * {@link #writesTheHeaderWithoutAnyRowWhereEasyExcelFailed()} as a fix, not a regression.
 */
class CsvWriterEasyExcelCompatibilityTest {

    @Test
    void matchesEasyExcelForPlainAndChineseValues() {
        assertMatchesEasyExcel(true, List.of("id", "名称"), List.of(
                List.of("1", "alpha"),
                List.of("2", "中文")));
    }

    @Test
    void matchesEasyExcelForDelimiterQuoteAndLineBreakFields() {
        assertMatchesEasyExcel(false, List.of("unused"), List.of(
                List.of("a,b", "say \"hi\"", "line1\nline2", "carriage\rreturn")));
    }

    @Test
    void matchesEasyExcelForEmptyStringsAndNulls() {
        assertMatchesEasyExcel(false, List.of("unused"), List.of(
                List.of("", "x"),
                List.of("x", ""),
                Arrays.asList("", null),
                Arrays.asList(null, ""),
                Arrays.asList(null, null)));
    }

    @Test
    void matchesEasyExcelForLeadingAndTrailingWhitespace() {
        assertMatchesEasyExcel(false, List.of("unused"), List.of(
                List.of(" leading", "trailing ", "plain", "\ttab")));
    }

    @Test
    void matchesEasyExcelWithAndWithoutHeader() {
        List<List<String>> rows = List.of(List.of("1", "alpha"));

        assertMatchesEasyExcel(true, List.of("id", "name"), rows);
        assertMatchesEasyExcel(false, List.of("id", "name"), rows);
    }

    @Test
    void writesTheHeaderWithoutAnyRowWhereEasyExcelFailed() {
        assertArrayEquals(
                new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'i', 'd', ',', 'n', 'a', 'm', 'e', '\r', '\n'},
                csvWriterBytes(true, List.of("id", "name"), List.of()));
        assertArrayEquals(
                new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                csvWriterBytes(false, List.of("id", "name"), List.of()));
    }

    private static void assertMatchesEasyExcel(boolean containsHeader, List<String> header,
            List<List<String>> rows) {
        byte[] expected = easyExcelBytes(containsHeader, header, rows);
        byte[] actual = csvWriterBytes(containsHeader, header, rows);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("EasyExcel=[" + describe(expected) + "] CsvWriter=[" + describe(actual) + "]");
        }
    }

    private static byte[] easyExcelBytes(boolean containsHeader, List<String> header, List<List<String>> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExcelWriterBuilder builder = EasyExcel.write(output)
                .charset(StandardCharsets.UTF_8)
                .excelType(ExcelTypeEnum.CSV);
        if (containsHeader) {
            builder.head(header.stream().map(List::of).toList());
        }
        ExcelWriter excelWriter = builder.build();
        WriteSheet writeSheet = EasyExcel.writerSheet(0).build();
        for (List<String> row : rows) {
            excelWriter.write(List.of(row), writeSheet);
        }
        excelWriter.finish();
        return output.toByteArray();
    }

    private static byte[] csvWriterBytes(boolean containsHeader, List<String> header, List<List<String>> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CsvWriter writer = new CsvWriter(output)) {
            writer.writeHeader(header, containsHeader);
            for (List<String> row : rows) {
                writer.writeRow(row);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return output.toByteArray();
    }

    private static String describe(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\uFEFF", "<BOM>");
    }
}
