package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

class ExcelParserTest {

    @Test
    void noHeaderRowsAreReturnedExactlyOnce() throws IOException {
        byte[] workbook = workbookWithData();

        ExcelParser.ExcelResult result = ExcelParser.parse(workbook, "data.xlsx", "visible", 0, 0,
                true, 50);

        Assertions.assertEquals(2, result.rows().size());
        Assertions.assertEquals(0, result.headerRowCount());
        Assertions.assertEquals("first", result.rows().get(0).get(0).value());
        Assertions.assertEquals("second", result.rows().get(1).get(0).value());
    }

    @Test
    void hiddenSheetsAreNeitherListedNorSelectable() throws IOException {
        byte[] workbook = workbookWithData();

        List<Map<String, Object>> sheets = ExcelParser.sheets(workbook, "data.xlsx");

        Assertions.assertEquals(List.of("visible"), sheets.stream().map(sheet -> (String) sheet.get("name")).toList());
        Assertions.assertThrows(BusinessException.class,
                () -> ExcelParser.parse(workbook, "data.xlsx", "hidden", 0, 0, true, 50));
    }

    @Test
    void preservesDecimalTextAndHonorsHeaderOffset() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("visible");
            sheet.createRow(0).createCell(0).setCellValue("intro");
            sheet.createRow(1).createCell(0).setCellValue("amount");
            sheet.createRow(2).createCell(0).setCellValue(0.1D);
            workbook.write(output);

            ExcelParser.ExcelResult result = ExcelParser.parse(output.toByteArray(), "data.xlsx", "visible", 1, 2,
                    true, 50);

            Assertions.assertEquals(1, result.headerRowCount());
            Assertions.assertEquals("amount", result.rows().get(0).get(0).value());
            Assertions.assertEquals("0.1", result.rows().get(1).get(0).value());
        }
    }

    @Test
    void parsesTypedCellsAndRejectsFormulasWhenConfigured() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("visible");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("amount");
            header.createCell(1).setCellValue("created_at");
            header.createCell(2).setCellValue("active");
            header.createCell(3).setCellValue("formula_total");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(0.1D);
            var dateCell = row.createCell(1);
            dateCell.setCellValue(new GregorianCalendar(2026, Calendar.AUGUST, 31, 8, 30, 0));
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            dateCell.setCellStyle(style);
            row.createCell(2).setCellValue(true);
            row.createCell(3).setCellFormula("A2*2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(row.getCell(3));
            workbook.write(output);

            ExcelParser.ExcelResult result = ExcelParser.parse(output.toByteArray(), "data.xlsx", "visible", 0, 1,
                    true, 50);

            Assertions.assertEquals("0.1", result.rows().get(1).get(0).value());
            Assertions.assertEquals("number", result.rows().get(1).get(0).type());
            Assertions.assertEquals("2026-08-31 08:30:00", result.rows().get(1).get(1).value());
            Assertions.assertEquals("date", result.rows().get(1).get(1).type());
            Assertions.assertEquals("true", result.rows().get(1).get(2).value());
            Assertions.assertEquals("boolean", result.rows().get(1).get(2).type());
            Assertions.assertEquals("0.2", result.rows().get(1).get(3).value());
            Assertions.assertEquals("formula", result.rows().get(1).get(3).type());

            Assertions.assertThrows(BusinessException.class,
                    () -> ExcelParser.parse(output.toByteArray(), "data.xlsx",
                            new ExcelImportConfig("visible", 0, 1, 0, true,
                                    ExcelImportConfig.FormulaMode.REJECT),
                            50));
        }
    }

    @Test
    void rejectsInvalidConfiguredRange() {
        Assertions.assertThrows(BusinessException.class,
                () -> ExcelImportConfig.from(Map.of("headerRow", 2, "startRow", 3, "endRow", 3)));
    }

    @Test
    void rejectsSparseWorkbookRowsBeforePreviewExpansion() throws IOException {
        byte[] workbook = workbookWithSparseCell(100_000, 0);

        BusinessException exception = Assertions.assertThrows(BusinessException.class,
                () -> ExcelParser.parse(workbook, "data.xlsx", "visible", 0, 1, true, 50));

        Assertions.assertEquals("import.excel.rowLimitExceeded", exception.getMessage());
    }

    @Test
    void rejectsSparseWorkbookColumnsBeforePreviewExpansion() throws IOException {
        byte[] workbook = workbookWithSparseCell(0, 1_024);

        BusinessException exception = Assertions.assertThrows(BusinessException.class,
                () -> ExcelParser.parse(workbook, "data.xlsx", "visible", 0, 1, true, 50));

        Assertions.assertEquals("import.excel.columnLimitExceeded", exception.getMessage());
    }

    @Test
    void rejectsOversizedWorkbookDimensionsBeforePreviewExpansion() throws IOException {
        byte[] workbook = workbookWithSparseCell(1_200, 900);

        BusinessException exception = Assertions.assertThrows(BusinessException.class,
                () -> ExcelParser.parse(workbook, "data.xlsx", "visible", 0, 1, true, 50));

        Assertions.assertEquals("import.excel.cellLimitExceeded", exception.getMessage());
    }

    private static byte[] workbookWithData() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("visible").createRow(0).createCell(0).setCellValue("first");
            workbook.getSheetAt(0).createRow(1).createCell(0).setCellValue("second");
            workbook.createSheet("hidden");
            workbook.setSheetHidden(1, true);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] workbookWithSparseCell(int rowIndex, int columnIndex) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("visible");
            sheet.createRow(0).createCell(0).setCellValue("id");
            sheet.createRow(rowIndex).createCell(columnIndex).setCellValue("value");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
