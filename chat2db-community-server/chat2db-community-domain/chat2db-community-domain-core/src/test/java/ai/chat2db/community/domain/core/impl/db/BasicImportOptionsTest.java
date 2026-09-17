package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.task.ExcelOptions;
import ai.chat2db.community.domain.api.model.task.JsonOptions;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CsvImportValueNormalizer;
import ai.chat2db.community.domain.core.impl.task.imports.reader.*;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.util.SqlSplitProcessor;
import com.alibaba.druid.DbType;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BasicImportOptionsTest {
    @TempDir Path directory;

    @Test
    void excelOptionsSelectSheetRowsAndColumnsAndPreserveNativeCells() throws Exception {
        for (String extension : List.of("xls", "xlsx")) {
            Path file = workbook(extension);
            ExcelOptions options = new ExcelOptions();
            options.setSheetIndex(1);
            options.setHeaderRow(2);
            options.setDataStartRow(3);
            options.setDataEndRow(3);
            options.setColumnRange(" b:e ");
            options.setDateOrder("DMY");
            options.setDateDelimiter("/");
            options.setYearDelimiter("/");
            options.setDecimalSymbol(",");
            Map<Integer, String> header = new LinkedHashMap<>();
            List<Map<Integer, ImportCell>> rows = new ArrayList<>();
            List<Integer> numbers = new ArrayList<>();
            ExcelImportReader.read(file.toFile(), options, 10, 0, header::putAll,
                    (row, number) -> { rows.add(row); numbers.add(number); }, () -> { });
            assertEquals(List.of("Ignored", "Data"), ExcelImportReader.sheets(file.toFile()));
            assertEquals(List.of("text_date", "native_date", "native_number", "empty"), new ArrayList<>(header.values()));
            assertEquals(List.of(3), numbers);
            assertEquals("16/09/2026", rows.get(0).get(0).value());
            assertTrue(rows.get(0).get(0).text());
            assertEquals(LocalDateTime.of(2026, 9, 16, 13, 14, 15), rows.get(0).get(1).value());
            assertFalse(rows.get(0).get(2).text());
            assertEquals("12.5", rows.get(0).get(2).display());
            assertNull(rows.get(0).get(3).value());
            var preview = new ImportPreviewFileParser().parse(file.toFile(), 10, null, options, null);
            assertEquals(header, preview.header());
            assertEquals(rows.get(0).get(0).display(), preview.data().get(0).get(0));
            options.setHasHeader(false);
            options.setDataStartRow(3);
            options.setEmptyAsNull(false);
            var noHeader = new ImportPreviewFileParser().parse(file.toFile(), 10, null, options, null);
            assertEquals("column_1", noHeader.header().get(0));
            assertEquals("", noHeader.data().get(0).get(3));
        }
    }

    @Test
    void excelRejectsInvalidRangesAndSheetIndexes() throws Exception {
        ExcelOptions options = new ExcelOptions();
        for (String range : List.of("H:B", "A", "A:XFE", "A:0")) {
            options.setColumnRange(range);
            assertThrows(BusinessException.class, options::validate);
        }
        options.setColumnRange("");
        options.setSheetIndex(8);
        Path file = workbook("xlsx");
        assertThrows(BusinessException.class, () -> ExcelImportReader.read(file.toFile(), options, 10, 0,
                ignored -> {}, (row, number) -> {}, () -> {}));
    }

    @Test
    void jsonReadsNestedArraysObjectsAndPreservesNullAndPrecision() throws Exception {
        Path file = directory.resolve("nested.json");
        Files.writeString(file, "{\"data\":{\"items\":[{\"id\":9007199254740993,\"user\":{\"name\":\"林\"},\"empty\":\"\",\"nil\":null,\"amount\":0.1234567890123456789}]}}");
        JsonOptions options = new JsonOptions();
        options.setDataPath("$.data.items");
        List<Map<String, ImportCell>> records = new ArrayList<>();
        JsonImportReader.read(file.toFile(), options, 10, (row, number) -> records.add(row), () -> {});
        assertEquals("9007199254740993", records.get(0).get("id").display());
        assertEquals("0.1234567890123456789", records.get(0).get("amount").display());
        assertEquals("林", records.get(0).get("user.name").value());
        assertEquals("", records.get(0).get("empty").value());
        assertNull(records.get(0).get("nil").value());
        options.setStructure("OBJECT");
        options.setDataPath("$.data.items[0]");
        options.setEmptyAsNull(true);
        records.clear();
        JsonImportReader.read(file.toFile(), options, 10, (row, number) -> records.add(row), () -> {});
        assertNull(records.get(0).get("empty").value());
        options.setDataPath("$.missing");
        assertThrows(BusinessException.class, () -> JsonImportReader.read(file.toFile(), options, 10,
                (row, number) -> {}, () -> {}));
    }

    @Test
    void jsonLinesUsesConfiguredEncodingAndUnionsPreviewFields() throws Exception {
        Path file = directory.resolve("lines.json");
        Files.writeString(file, "{\"id\":1,\"name\":\"中文\"}\n\n{\"id\":2,\"note\":\"other\"}\n", Charset.forName("GBK"));
        JsonOptions options = new JsonOptions();
        options.setEncoding("GBK");
        options.setStructure("LINES");
        var preview = new ImportPreviewFileParser().parse(file.toFile(), 10, null, null, options);
        assertEquals(List.of("id", "name", "note"), new ArrayList<>(preview.header().values()));
        assertEquals("中文", preview.data().get(0).get(1));
        assertEquals("other", preview.data().get(1).get(2));
        options.setEncoding("UTF-8");
        assertThrows(BusinessException.class, () -> new ImportPreviewFileParser().parse(file.toFile(), 10, null, null, options));
    }

    @Test
    void textValueFormatsAreSharedWithCsvWhileNativeDatesIgnoreTextOrder() {
        ExcelOptions options = new ExcelOptions();
        options.setDateOrder("DMY"); options.setDateDelimiter("/"); options.setYearDelimiter("/");
        options.setDateTimeOrder("TIME_DATE"); options.setTimeDelimiter("."); options.setDecimalSymbol(",");
        TableColumn timestamp = TableColumn.builder().name("created").dataType(Types.TIMESTAMP).build();
        assertEquals("2026-09-16 13:14:15", CsvImportValueNormalizer.normalize("13.14.15 16/09/2026", timestamp, options, 3));
        TableColumn date = TableColumn.builder().name("date").dataType(Types.DATE).build();
        assertEquals("2026-09-16", CsvImportValueNormalizer.nativeValue(LocalDateTime.of(2026,9,16,13,14), date));
        TableColumn decimal = TableColumn.builder().name("amount").dataType(Types.DECIMAL).build();
        assertEquals("12.5", CsvImportValueNormalizer.normalize("12,5", decimal, options, 3));
    }

    @Test
    void sqlDecodingFeedsDialectSplittingOfQuotedSemicolons() throws Exception {
        Path file = directory.resolve("script.sql");
        Files.writeString(file, "INSERT INTO t VALUES ('中文;分号');\n-- ; comment\nINSERT INTO t VALUES ('second');",
                Charset.forName("GBK"));
        Path utf8 = ImportTextFile.utf8Copy(file.toFile(), "GBK", () -> {});
        try (var stream = Files.newInputStream(utf8)) {
            var iterator = SqlSplitProcessor.iterator(stream, StandardCharsets.UTF_8,
                    new SqlSplitProcessor(DbType.mysql, false, false));
            List<String> statements = new ArrayList<>();
            while (iterator.hasNext()) statements.add(iterator.next().getStr().trim());
            assertEquals(2, statements.size());
            assertTrue(statements.get(0).contains("中文;分号"));
            assertTrue(statements.get(1).contains("second"));
        } finally { Files.deleteIfExists(utf8); }
    }

    @Test
    void rejectsTrailingJsonDocumentsDuringExecution() throws Exception {
        Path file = directory.resolve("trailing.json");
        Files.writeString(file, "[{\"id\":1}] {\"id\":2}");
        assertThrows(BusinessException.class, () -> JsonImportReader.read(file.toFile(), new JsonOptions(),
                Integer.MAX_VALUE, (row, number) -> { }, () -> { }));
    }

    @Test
    void jsonPreviewExplainsEncodingAndNodeErrorsAndRecovers() throws Exception {
        Path file = directory.resolve("nested-gbk.json");
        Files.writeString(file, "{\"data\":{\"items\":[{\"id\":1,\"name\":\"中文\"}]}}", Charset.forName("GBK"));
        JsonOptions options = new JsonOptions();
        for (String structure : List.of("ARRAY", "OBJECT", "LINES")) {
            options.setStructure(structure);
            BusinessException error = assertThrows(BusinessException.class,
                    () -> new ImportPreviewFileParser().parse(file.toFile(), 10, null, null, options));
            assertEquals("import.preview.jsonEncodingMismatch", error.getCode());
            assertArrayEquals(new Object[]{"UTF-8"}, error.getArgs());
        }
        options.setEncoding("GBK");
        options.setStructure("ARRAY");
        BusinessException structure = assertThrows(BusinessException.class,
                () -> new ImportPreviewFileParser().parse(file.toFile(), 10, null, null, options));
        assertEquals("import.preview.jsonExpectedArray", structure.getCode());
        assertArrayEquals(new Object[]{"$"}, structure.getArgs());
        options.setDataPath("$.data.missing");
        BusinessException missing = assertThrows(BusinessException.class,
                () -> new ImportPreviewFileParser().parse(file.toFile(), 10, null, null, options));
        assertEquals("import.preview.jsonPathNotFound", missing.getCode());
        assertArrayEquals(new Object[]{"$.data.missing"}, missing.getArgs());
        options.setDataPath("$.data.items");
        options.setStructure("OBJECT");
        BusinessException object = assertThrows(BusinessException.class,
                () -> new ImportPreviewFileParser().parse(file.toFile(), 10, null, null, options));
        assertEquals("import.preview.jsonExpectedObject", object.getCode());
        options.setStructure("ARRAY");
        var preview = new ImportPreviewFileParser().parse(file.toFile(), 10, null, null, options);
        assertEquals("中文", preview.data().get(0).get(1));
    }

    @Test
    void jsonSyntaxErrorsReportPositionsWithoutEchoingFileContents() throws Exception {
        Path file = directory.resolve("invalid.json");
        Files.writeString(file, "[\n{\"name\": private_value}\n]");
        JsonOptions options = new JsonOptions();
        BusinessException error = assertThrows(BusinessException.class,
                () -> new ImportPreviewFileParser().parse(file.toFile(), 10, null, null, options));
        assertEquals("import.preview.jsonInvalidSyntax", error.getCode());
        assertEquals(2, error.getArgs()[0]);
        assertTrue((Integer) error.getArgs()[1] > 0);
        options.setStructure("LINES");
        Files.writeString(file, "{\"id\":1}\n{\"name\": private_value}\n");
        BusinessException line = assertThrows(BusinessException.class,
                () -> new ImportPreviewFileParser().parse(file.toFile(), 10, null, null, options));
        assertEquals("import.preview.jsonInvalidLine", line.getCode());
        assertArrayEquals(new Object[]{2}, line.getArgs());
    }

    @Test
    void jsonPreviewDiscoversFieldsAfterThePreviewLimit() throws Exception {
        Path file = directory.resolve("late-field.json");
        List<String> records = new ArrayList<>();
        for (int id = 1; id <= 11; id++) {
            records.add(id == 11 ? "{\"id\":11,\"note\":\"late\"}" : "{\"id\":" + id + "}");
        }
        Files.writeString(file, "[" + String.join(",", records) + "]");
        ImportPreviewFileParser.ParsedRows preview = new ImportPreviewFileParser()
                .parse(file.toFile(), 10, null, null, new JsonOptions());
        assertEquals(List.of("id", "note"), new ArrayList<>(preview.header().values()));
        assertEquals(10, preview.data().size());
    }

    @Test
    void excelFillsTrailingMissingCellsAccordingToEmptyAsNull() throws Exception {
        Path file = directory.resolve("sparse.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue("id");
            sheet.getRow(0).createCell(1).setCellValue("note");
            sheet.createRow(1).createCell(0).setCellValue(1);
            sheet.createRow(2).createCell(0).setCellValue(2);
            sheet.getRow(2).createCell(1).setBlank();
            try (var out = Files.newOutputStream(file)) { workbook.write(out); }
        }
        ExcelOptions options = new ExcelOptions();
        options.setEmptyAsNull(false);
        List<Map<Integer, ImportCell>> rows = new ArrayList<>();
        ExcelImportReader.read(file.toFile(), options, 10, 0, ignored -> { },
                (row, number) -> rows.add(row), () -> { });
        assertEquals(2, rows.size());
        assertEquals("", rows.get(0).get(1).display());
        assertEquals("", rows.get(1).get(1).display());
    }

    @Test
    void invalidExcelFilesHaveActionableCodes() throws Exception {
        for (String extension : List.of("xls", "xlsx")) {
            Path invalid = directory.resolve("invalid." + extension);
            Files.writeString(invalid, "not an Excel workbook");
            BusinessException unreadable = assertThrows(BusinessException.class,
                    () -> new ImportPreviewFileParser().parse(invalid.toFile(), 10, null));
            assertEquals("import.preview.invalidExcelFile", unreadable.getCode());

        }
    }

    @Test
    void optionErrorsIdentifyTheIncorrectSetting() throws Exception {
        ExcelOptions excel = new ExcelOptions();
        excel.setDataStartRow(1);
        assertEquals("import.preview.invalidRowRange", assertThrows(BusinessException.class, excel::validate).getCode());
        excel.setDataStartRow(2);
        excel.setDateOrder("INVALID");
        assertEquals("import.preview.invalidValueFormat", assertThrows(BusinessException.class, excel::validate).getCode());
        Path file = directory.resolve("gbk.sql");
        Files.writeString(file, "INSERT INTO t VALUES ('中文');", Charset.forName("GBK"));
        BusinessException encoding = assertThrows(BusinessException.class,
                () -> ImportTextFile.utf8Copy(file.toFile(), "UTF-8", () -> { }));
        assertEquals("import.sql.encodingMismatch", encoding.getCode());
        assertArrayEquals(new Object[]{"UTF-8"}, encoding.getArgs());
    }

    private Path workbook(String extension) throws Exception {
        Path file = directory.resolve("sample." + extension);
        try (Workbook workbook = "xls".equals(extension) ? new HSSFWorkbook() : new XSSFWorkbook()) {
            workbook.createSheet("Ignored").createRow(0).createCell(0).setCellValue("wrong");
            var sheet = workbook.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue("title");
            var header = sheet.createRow(1);
            String[] names = {"excluded", "text_date", "native_date", "native_number", "empty"};
            for (int index = 0; index < names.length; index++) header.createCell(index).setCellValue(names[index]);
            var data = sheet.createRow(2);
            data.createCell(0).setCellValue("excluded");
            data.createCell(1).setCellValue("16/09/2026");
            data.createCell(2).setCellValue(LocalDateTime.of(2026, 9, 16, 13, 14, 15));
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            data.getCell(2).setCellStyle(style);
            data.createCell(3).setCellValue(12.5);
            sheet.createRow(3).createCell(1).setCellValue("excluded footer");
            try (var out = Files.newOutputStream(file)) { workbook.write(out); }
        }
        return file;
    }
}
