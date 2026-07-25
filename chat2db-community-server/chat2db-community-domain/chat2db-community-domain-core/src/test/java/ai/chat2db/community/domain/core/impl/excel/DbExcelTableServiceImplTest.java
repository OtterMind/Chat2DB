package ai.chat2db.community.domain.core.impl.excel;

import ai.chat2db.community.domain.api.model.excel.ExcelCheckResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbExcelTableServiceImplTest {

    @Test
    void generatedDdlEscapesQuotedIdentifiersAndApostrophesInComments() throws Exception {
        ExcelCheckResponse.Header header = new ExcelCheckResponse.Header();
        header.setHeaderName("owner\"name");
        header.setDataType("varchar(32)");
        header.setComment("O'Brien");
        ExcelCheckResponse.Sheet sheet = new ExcelCheckResponse.Sheet();
        sheet.setTableName("team\"data");
        sheet.setHeaderList(List.of(header));

        DbExcelTableServiceImpl service = new DbExcelTableServiceImpl(null, null, null);
        Method buildCreateTable = DbExcelTableServiceImpl.class
                .getDeclaredMethod("buildCreateTable", List.class);
        buildCreateTable.setAccessible(true);
        buildCreateTable.invoke(service, List.of(sheet));

        assertTrue(sheet.getDdl().contains("CREATE TABLE \"team\"\"data\""));
        assertTrue(sheet.getDdl().contains("\"owner\"\"name\" varchar(32)"));
        assertTrue(sheet.getDdl().contains("COMMENT 'O''Brien'"));

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:excel-ddl-escaping");
             Statement statement = connection.createStatement()) {
            statement.execute(sheet.getDdl());
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT \"owner\"\"name\" FROM \"team\"\"data\"")) {
                assertFalse(resultSet.next());
            }
        }
    }
}
