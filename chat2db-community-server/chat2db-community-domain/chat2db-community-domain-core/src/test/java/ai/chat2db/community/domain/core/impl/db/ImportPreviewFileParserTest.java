package ai.chat2db.community.domain.core.impl.db;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportPreviewFileParserTest {

    @Test
    void parsesXlsWithoutCsvOptions(@TempDir Path directory) {
        assertExcelPreview(directory.resolve("contacts.xls"), ExcelTypeEnum.XLS);
    }

    @Test
    void parsesXlsxWithoutCsvOptions(@TempDir Path directory) {
        assertExcelPreview(directory.resolve("contacts.xlsx"), ExcelTypeEnum.XLSX);
    }

    private void assertExcelPreview(Path path, ExcelTypeEnum type) {
        EasyExcel.write(path.toFile())
                .excelType(type)
                .head(List.of(List.of("Name"), List.of("Age")))
                .sheet()
                .doWrite(List.of(List.of("Alice", 30), List.of("Bob", 31)));

        ImportPreviewFileParser.ParsedRows result = new ImportPreviewFileParser().parse(path.toFile(), 10, null);

        assertEquals(List.of("Name", "Age"), List.copyOf(result.header().values()));
        assertEquals("Alice", result.data().get(0).get(0));
        assertEquals("30", result.data().get(0).get(1));
        assertEquals("Bob", result.data().get(1).get(0));
        assertEquals(false, result.syntheticHeader());
    }
}
