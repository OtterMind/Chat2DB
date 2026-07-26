package ai.chat2db.plugin.sqlserver;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlServerMetaDataTest {

    @Test
    void shouldPreserveEitherNonDefaultIdentityParameterWithoutIntegerTruncation() {
        assertEquals("BIGINT identity",
                SqlServerMetaData.buildIdentityDataType("BIGINT", BigDecimal.ONE, BigDecimal.ONE));
        assertEquals("BIGINT identity (10,1)",
                SqlServerMetaData.buildIdentityDataType("BIGINT", BigDecimal.TEN, BigDecimal.ONE));
        assertEquals("BIGINT identity (1,5)",
                SqlServerMetaData.buildIdentityDataType("BIGINT", BigDecimal.ONE, BigDecimal.valueOf(5)));
        assertEquals("DECIMAL identity (9223372036854775808,-2)",
                SqlServerMetaData.buildIdentityDataType("DECIMAL",
                        new BigDecimal("9223372036854775808"), new BigDecimal("-2")));
    }

    @Test
    void shouldNotRenderJdbcLengthForFixedSizeOrRowVersionTypes() {
        assertTrue(SqlServerMetaData.shouldOmitColumnSize("TIMESTAMP"));
        assertTrue(SqlServerMetaData.shouldOmitColumnSize("ROWVERSION"));
        assertTrue(SqlServerMetaData.shouldOmitColumnSize("FLOAT"));
        assertFalse(SqlServerMetaData.shouldOmitColumnSize("DECIMAL"));
    }

    @Test
    void shouldRenderForeignKeyActionsIndependently() {
        assertEquals("", SqlServerMetaData.buildReferentialActions(0, 0));
        assertEquals(" on update cascade", SqlServerMetaData.buildReferentialActions(1, 0));
        assertEquals(" on delete set null", SqlServerMetaData.buildReferentialActions(0, 2));
        assertEquals(" on delete cascade on update set default",
                SqlServerMetaData.buildReferentialActions(3, 1));
    }

    @Test
    void shouldQuoteEveryForeignKeyIdentifierPart() {
        assertEquals("constraint [FK orders]]owner]\n"
                        + "foreign key ([order id] , [line]]id])\n"
                        + "references [sales archive].[order]]history] ([id]) on delete set null",
                SqlServerMetaData.buildForeignKeyDefinition(
                        "FK orders]owner", List.of("order id", "line]id"),
                        "sales archive", "order]history", List.of("id"), 0, 2));
    }
}
