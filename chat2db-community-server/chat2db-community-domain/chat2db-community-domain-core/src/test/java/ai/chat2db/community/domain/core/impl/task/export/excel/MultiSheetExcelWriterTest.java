package ai.chat2db.community.domain.core.impl.task.export.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiSheetExcelWriterTest {

    @Test
    void createsAHeaderOnlySheetForAnEmptyResult() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExcelWriter excelWriter = EasyExcel.write(outputStream).excelType(ExcelTypeEnum.XLSX).build()) {
            MultiSheetExcelWriter writer = new MultiSheetExcelWriter(excelWriter,
                    List.of(List.of("id"), List.of("name")), 3, "Data");

            writer.initialize();

            assertEquals(1, writer.getSheetCount());
            assertEquals(0L, writer.getTotalRows());
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(outputStream.toByteArray()))) {
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals(1, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("id", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    void rollsOverToANewSheetAndRepeatsTheHeader() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExcelWriter excelWriter = EasyExcel.write(outputStream).excelType(ExcelTypeEnum.XLSX).build()) {
            MultiSheetExcelWriter writer = new MultiSheetExcelWriter(excelWriter,
                    List.of(List.of("id"), List.of("name")), 3, "Export/Data");

            writer.writeRow(List.of("1", "alpha"));
            writer.writeRow(List.of("2", "beta"));
            writer.writeRow(List.of("3", "gamma"));

            assertEquals(2, writer.getSheetCount());
            assertEquals(3L, writer.getTotalRows());
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(outputStream.toByteArray()))) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals("Export Data", workbook.getSheetName(0));
            assertEquals("Export Data (2)", workbook.getSheetName(1));
            assertEquals(3, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals(2, workbook.getSheetAt(1).getPhysicalNumberOfRows());
            assertEquals("id", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("name", workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
            assertEquals("1", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("alpha", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals("id", workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue());
            assertEquals("name", workbook.getSheetAt(1).getRow(0).getCell(1).getStringCellValue());
            assertEquals("2", workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue());
            assertEquals("beta", workbook.getSheetAt(0).getRow(2).getCell(1).getStringCellValue());
            assertEquals("3", workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue());
            assertEquals("gamma", workbook.getSheetAt(1).getRow(1).getCell(1).getStringCellValue());
        }
    }

    @Test
    void usesTheFullSheetCapacityWhenThereIsNoHeader() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExcelWriter excelWriter = EasyExcel.write(outputStream).excelType(ExcelTypeEnum.XLSX).build()) {
            MultiSheetExcelWriter writer = new MultiSheetExcelWriter(excelWriter, List.of(), 2, "Data");

            writer.initialize();
            writer.writeRow(List.of("1"));
            writer.writeRow(List.of("2"));
            writer.writeRow(List.of("3"));
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(outputStream.toByteArray()))) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals(2, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals(1, workbook.getSheetAt(1).getPhysicalNumberOfRows());
            assertEquals("1", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("2", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("3", workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    void rollsOverXlsSheetsAtTheConfiguredRowLimit() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExcelWriter excelWriter = EasyExcel.write(outputStream).excelType(ExcelTypeEnum.XLS).build()) {
            MultiSheetExcelWriter writer = new MultiSheetExcelWriter(excelWriter, List.of(List.of("id")), 3,
                    "Data");

            writer.writeRow(List.of("1"));
            writer.writeRow(List.of("2"));
            writer.writeRow(List.of("3"));
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(outputStream.toByteArray()))) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals(3, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals(2, workbook.getSheetAt(1).getPhysicalNumberOfRows());
        }
    }

    @Test
    void keepsRolloverSheetNamesUniqueWithinTheExcelLimit() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExcelWriter excelWriter = EasyExcel.write(outputStream).excelType(ExcelTypeEnum.XLSX).build()) {
            MultiSheetExcelWriter writer = new MultiSheetExcelWriter(excelWriter, List.of(List.of("id")), 2,
                    "x".repeat(31));

            writer.writeRow(List.of("1"));
            writer.writeRow(List.of("2"));
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(outputStream.toByteArray()))) {
            assertEquals("x".repeat(31), workbook.getSheetName(0));
            assertEquals("x".repeat(27) + " (2)", workbook.getSheetName(1));
        }
    }
}
