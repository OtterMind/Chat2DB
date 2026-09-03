package ai.chat2db.plugin.sqlserver;

import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerIndexTypeEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    @Test
    void shouldPreserveIndexColumnSortDirectionFromSqlServerMetadata() {
        List<TableIndex> indexes = new SqlServerMetaData().indexes(
                indexMetadataConnection(), "catalog", "dbo", "orders");

        assertEquals(1, indexes.size());
        TableIndex index = indexes.get(0);
        assertEquals(List.of("DESC", "ASC"), index.getColumnList().stream()
                .map(TableIndexColumn::getAscOrDesc)
                .toList());

        String script = SqlServerIndexTypeEnum.NONCLUSTERED.buildIndexScript(index);
        assertTrue(script.contains("([created_at] DESC,[id] ASC)"), script);
    }

    private static Connection indexMetadataConnection() {
        ResultSet resultSet = indexMetadataResultSet();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                SqlServerMetaDataTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "getResultSet" -> resultSet;
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                SqlServerMetaDataTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> "prepareStatement".equals(method.getName())
                        ? statement : defaultValue(method.getReturnType()));
    }

    private static ResultSet indexMetadataResultSet() {
        int[] row = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                SqlServerMetaDataTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++row[0] < 2;
                    case "getString" -> switch ((String) args[0]) {
                        case "INDEX_NAME" -> "IX_orders_sort";
                        case "COLUMN_NAME" -> row[0] == 0 ? "created_at" : "id";
                        case "INDEX_TYPE" -> "NONCLUSTERED";
                        default -> null;
                    };
                    case "getInt" -> "DESCEND".equals(args[0]) && row[0] == 0 ? 1 : 0;
                    case "getShort" -> (short) (row[0] + 1);
                    case "getBoolean" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
