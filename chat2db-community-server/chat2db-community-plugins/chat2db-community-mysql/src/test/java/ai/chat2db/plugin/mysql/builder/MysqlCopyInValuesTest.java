package ai.chat2db.plugin.mysql.builder;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.plugin.mysql.MysqlPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlCopyInValuesTest {
    private static final String TEST_DB_TYPE = "TEST_MYSQL_COPY_IN_VALUES";
    private final MysqlSqlBuilder builder = new MysqlSqlBuilder();

    @BeforeEach
    void setUp() {
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new MysqlPlugin());
        ConnectInfo info = new ConnectInfo();
        info.setDbType(TEST_DB_TYPE);
        info.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(info);
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
    }

    @Test
    void copiesFullyLoadedJsonAndTextWithDialectEscaping() {
        String json = "{\"text\":\"中文😀'\\\\\",\"tail\":\"END_JSON\"}";
        assertEquals("(CAST('" + json.replace("\\", "\\\\").replace("'", "''") + "' AS JSON))",
                copy("JSON", json, ResultCell.of(json)));
        assertEquals("('O''Brien\\\\docs')",
                copy("LONGTEXT", "O'Brien\\docs", ResultCell.of("O'Brien\\docs")));
    }

    @Test
    void copiesFullyLoadedBinaryAsSqlHexLiteral() {
        String hex = "0x00017F80FF";
        ResultCell cell = ResultCell.of(hex);
        cell.setValueType("BINARY");
        assertEquals("(" + hex + ")", copy("LONGBLOB", hex, cell));
    }

    @Test
    void keepsRejectingEveryIncompleteValueMarker() {
        ResultCell large = ResultCell.of("preview");
        large.setLargeValue(true);
        ResultCell truncated = ResultCell.of("preview");
        truncated.setTruncated(true);
        ResultCell token = ResultCell.of("preview");
        token.setLargeValueId("unresolved-token");
        for (ResultCell cell : List.of(large, truncated, token)) {
            assertThrows(BusinessException.class, () -> copy("JSON", "preview", cell));
        }
        assertThrows(BusinessException.class,
                () -> copy("JSON", "CHAT2DB_LARGE_VALUE_PREVIEW:preview", ResultCell.of("preview")));
    }

    @Test
    void rejectsMissingCellMetadata() {
        assertThrows(BusinessException.class, () -> copy("JSON", "preview", null));
    }

    private String copy(String columnType, String value, ResultCell cell) {
        Header header = new Header();
        header.setName("value");
        header.setColumnType(columnType);
        ResultOperation operation = new ResultOperation();
        operation.setSelectCols(List.of(0));
        operation.setDataList(List.of(value));
        operation.setSelectedCell(cell);
        QueryResponse response = new QueryResponse();
        response.setHeaderList(List.of(header));
        response.setOperations(List.of(operation));
        return builder.copyInValuesByQuery(response);
    }
}
