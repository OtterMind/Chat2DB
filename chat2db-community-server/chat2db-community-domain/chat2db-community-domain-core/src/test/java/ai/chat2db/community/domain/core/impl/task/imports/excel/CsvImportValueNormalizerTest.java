package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvImportValueNormalizerTest {

    @Test
    void normalizesConfiguredDateTimeAndDecimalFormats() {
        CsvOptions options = CsvOptions.builder()
                .dateOrder("DMY")
                .dateTimeOrder("DATE_TIME")
                .dateDelimiter("/")
                .timeDelimiter(":")
                .decimalSymbol(",")
                .build().validate();

        assertEquals("2023-08-24", normalize("24/August/23", Types.DATE, options));
        assertEquals("2023-08-24 15:30:38", normalize("24/8/2023 15:30:38", Types.TIMESTAMP, options));
        assertEquals("15:30:38", normalize("15:30:38", Types.TIME, options));
        assertEquals("12.50", normalize("12,50", Types.DECIMAL, options));
    }

    @Test
    void supportsTimeFirstAndASeparateYearDelimiter() {
        CsvOptions options = CsvOptions.builder()
                .dateOrder("DMY")
                .dateTimeOrder("TIME_DATE")
                .dateDelimiter("/")
                .yearDelimiter("-")
                .timeDelimiter(".")
                .build().validate();

        assertEquals("2023-08-24 15:30:38", normalize("15.30.38 24/8-2023", Types.TIMESTAMP, options));
    }

    @Test
    void supportsEveryDateComponentAndTimezonePosition() {
        CsvOptions monthYearDay = CsvOptions.builder()
                .dateOrder("MYD").dateDelimiter("/").build().validate();
        assertEquals("2023-08-24", normalize("August/23/24", Types.DATE, monthYearDay));

        CsvOptions timeZoneDate = CsvOptions.builder()
                .dateOrder("DMY")
                .dateTimeOrder("TIME_TIMEZONE_DATE")
                .dateDelimiter("/")
                .timeDelimiter(":")
                .build().validate();
        assertEquals("2023-08-24 15:30:38+08:00",
                normalize("15:30:38 +08:00 24/8/23", Types.TIMESTAMP_WITH_TIMEZONE, timeZoneDate));
    }

    @Test
    void reportsTheSourceRowColumnAndValueForInvalidTypedData() {
        CsvOptions options = CsvOptions.builder().dateOrder("YMD").dateDelimiter("-").build().validate();
        TableColumn column = TableColumn.builder().name("created_at").dataType(Types.DATE).build();

        BusinessException error = assertThrows(BusinessException.class,
                () -> CsvImportValueNormalizer.normalize("24/8/2023", column, options, 7));

        assertEquals("import.csv.invalidValue", error.getCode());
        assertEquals(7L, error.getArgs()[0]);
        assertEquals("created_at", error.getArgs()[1]);
        assertEquals("24/8/2023", error.getArgs()[2]);
    }

    @Test
    void fallsBackToDatabaseColumnTypeWhenJdbcTypeIsMissing() {
        CsvOptions options = CsvOptions.builder()
                .dateOrder("YDM")
                .dateTimeOrder("TIME_DATE")
                .dateDelimiter("/")
                .timeDelimiter(".")
                .decimalSymbol(",")
                .build().validate();

        assertEquals("2023-08-24", normalizeByColumnType("2023/24/8", "DATE", options));
        assertEquals("2023-08-24 15:30:38",
                normalizeByColumnType("15.30.38 2023/24/8", "TIMESTAMP", options));
        assertEquals("12.50", normalizeByColumnType("12,50", "DECIMAL(10,2)", options));
    }

    @Test
    void keepsDefaultIsoAndDateOnlyTimestampFormsCompatible() {
        CsvOptions options = CsvOptions.defaults().validate();

        assertEquals("2023-08-24 15:30:38", normalize("2023-08-24T15:30:38", Types.TIMESTAMP, options));
        assertEquals("2023-08-24 00:00", normalize("2023-08-24", Types.TIMESTAMP, options));
    }

    private static String normalize(String value, int type, CsvOptions options) {
        TableColumn column = TableColumn.builder().name("value").dataType(type).build();
        return CsvImportValueNormalizer.normalize(value, column, options, 2);
    }

    private static String normalizeByColumnType(String value, String columnType, CsvOptions options) {
        TableColumn column = TableColumn.builder().name("value").columnType(columnType).build();
        return CsvImportValueNormalizer.normalize(value, column, options, 2);
    }
}
