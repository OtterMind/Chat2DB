package ai.chat2db.spi;

import ai.chat2db.spi.model.request.KeyBound;
import ai.chat2db.spi.model.request.KeysetPageLimitRequest;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.SelectKeyRangeSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeysetSqlBuilderTest {

    private final DefaultSqlBuilder builder = new DefaultSqlBuilder();

    @Test
    void singleKeyAscendingPageCarriesTheDialectPageLimit() {
        String sql = builder.buildKeysetPageLimit(KeysetPageLimitRequest.builder()
                .databaseName("shop")
                .tableName("orders")
                .keyColumns(List.of("id"))
                .bounds(List.of(new KeyBound("id", "42", true)))
                .fetchSize(500)
                .build());

        assertEquals("SELECT * FROM shop.orders WHERE (id > 42) ORDER BY id ASC\n LIMIT 500", sql);
    }

    @Test
    void emptyBoundsProduceThePlainOrderedCursorQuery() {
        String sql = builder.buildKeysetPageLimit(KeysetPageLimitRequest.builder()
                .tableName("orders")
                .keyColumns(List.of("id"))
                .fetchSize(1000)
                .build());

        assertEquals("SELECT * FROM orders ORDER BY id ASC\n LIMIT 1000", sql);
    }

    @Test
    void compositeCursorExpandsIntoThePrefixEqualityChain() {
        String sql = builder.buildKeysetPageLimit(KeysetPageLimitRequest.builder()
                .tableName("orders")
                .columnList(List.of("id", "total"))
                .keyColumns(List.of("status", "id"))
                .bounds(List.of(new KeyBound("status", "'paid'", true), new KeyBound("id", "7", true)))
                .fetchSize(2)
                .build());

        assertEquals("SELECT id,total FROM orders WHERE (status > 'paid')"
                + " OR (status = 'paid' AND id > 7) ORDER BY status ASC,id ASC\n LIMIT 2", sql);
    }

    @Test
    void descendingBoundFlipsBothOperatorAndOrder() {
        String sql = builder.buildKeysetPageLimit(KeysetPageLimitRequest.builder()
                .tableName("logs")
                .keyColumns(List.of("ts"))
                .bounds(List.of(new KeyBound("ts", "1700000000", false)))
                .fetchSize(10)
                .build());

        assertEquals("SELECT * FROM logs WHERE (ts < 1700000000) ORDER BY ts DESC\n LIMIT 10", sql);
    }

    @Test
    void pagingSuffixFollowsADialectBuildPageLimitOverride() {
        DefaultSqlBuilder sqlServerLike = new DefaultSqlBuilder() {
            @Override
            public String buildPageLimit(PageLimitRequest request) {
                return request.getSql() + " OFFSET 0 ROWS FETCH NEXT " + request.getPageSize() + " ROWS ONLY";
            }
        };

        String sql = sqlServerLike.buildKeysetPageLimit(KeysetPageLimitRequest.builder()
                .tableName("orders")
                .keyColumns(List.of("id"))
                .bounds(List.of(new KeyBound("id", "9", true)))
                .fetchSize(50)
                .build());

        assertEquals("SELECT * FROM orders WHERE (id > 9) ORDER BY id ASC"
                + " OFFSET 0 ROWS FETCH NEXT 50 ROWS ONLY", sql);
    }

    @Test
    void keyRangeSelectsMinMaxOfTheKey() {
        String sql = builder.buildSelectKeyRange(SelectKeyRangeSqlRequest.builder()
                .schemaName("public")
                .tableName("orders")
                .keyColumn("id")
                .build());

        assertEquals("SELECT MIN(id),MAX(id) FROM public.orders", sql);
    }

    @Test
    void keysetQueryWithoutAKeyColumnIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> builder.buildKeysetPageLimit(
                KeysetPageLimitRequest.builder().tableName("orders").fetchSize(10).build()));
        assertThrows(IllegalArgumentException.class, () -> builder.buildSelectKeyRange(
                SelectKeyRangeSqlRequest.builder().tableName("orders").build()));
    }
}
