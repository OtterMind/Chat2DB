package ai.chat2db.community.domain.core.impl.task.export.sink;

import ai.chat2db.community.domain.api.model.task.pipeline.ExportSchema;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.metadata.WriteSheet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CSV sink must be byte-identical to the EasyExcel writer it replaced, including the UTF-8 BOM
 * and the minimal-quoting rules that Excel round-trips depend on.
 */
class CsvSinkTest {

    @SafeVarargs
    private static <T> List<T> mutable(T... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static final List<List<String>> HEAD = List.of(
            List.of("id"), List.of("name"), List.of("note"), List.of("flag"));

    private static final List<List<Object>> ROWS = List.of(
            mutable(1, "plain", "with space inside", "true"),
            mutable(2, "", null, " withLeading"),
            mutable(3, "with,comma", "with\"quote", "with\nnewline"),
            mutable(4, "with\r\nCRLF", "trailing ", "tab\tinside"));

    @Test
    void writesTheSameBytesAsTheLegacyEasyExcelCsvWriter() throws IOException {
        byte[] expected;
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(legacy).charset(StandardCharsets.UTF_8)
                .excelType(ExcelTypeEnum.CSV).build()) {
            WriteSheet sheet = EasyExcel.writerSheet("Data").build();
            sheet.setHead(HEAD);
            writer.write(ROWS, sheet);
        }
        expected = legacy.toByteArray();

        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        CsvSink sink = new CsvSink(actual, true);
        sink.writeSchema(new ExportSchema(HEAD.stream().map(column -> column.get(0)).toList()), "Data");
        sink.writeRows(ROWS);
        sink.finishTable("Data");
        sink.close();

        assertArrayEquals(expected, actual.toByteArray());
    }

    @Test
    void quotesEmptyAndPaddedFieldsDoublesQuotesAndOmitsNulls() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvSink sink = new CsvSink(out, false);
        sink.writeSchema(new ExportSchema(List.of("a", "b", "c")), "t");
        sink.writeRows(List.of(mutable("", " x", null), mutable("a,b", "q\"q", "line\nbreak")));
        sink.close();

        String csv = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿"), "BOM");
        assertEquals("﻿\"\",\" x\",<CR><LF>\"a,b\",\"q\"\"q\",\"line<LF>break\"<CR><LF>",
                csv.replace("\r", "<CR>").replace("\n", "<LF>"));
    }

    @Test
    void omitsTheHeaderRowWhenDisabledAndCountsBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvSink sink = new CsvSink(out, false);
        sink.writeSchema(new ExportSchema(List.of("a")), "t");
        sink.writeRows(List.of(mutable("v")));
        sink.close();

        assertEquals("﻿v<CR><LF>",
                new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r", "<CR>")
                        .replace("\n", "<LF>"));
        assertEquals(out.size(), sink.bytesWritten());
    }
}
