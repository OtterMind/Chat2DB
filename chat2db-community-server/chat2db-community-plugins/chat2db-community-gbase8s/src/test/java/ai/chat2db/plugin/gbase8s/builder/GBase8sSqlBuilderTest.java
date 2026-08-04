package ai.chat2db.plugin.gbase8s.builder;

import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GBase8sSqlBuilderTest {

    private final GBase8sSqlBuilder builder = new GBase8sSqlBuilder();

    @Test
    void blankQualifiedIdentifiersAreOmitted() {
        assertEquals("", builder.quoteQualifiedIdentifier());
        assertEquals("", builder.quoteQualifiedIdentifier((String) null));
        assertEquals("", builder.quoteQualifiedIdentifier("   "));
    }

    @Test
    void selectAndCountUseDatabaseColonAndOwnerDot() {
        assertEquals("SELECT * FROM inventory:gbasedbt.orders",
                builder.dql().buildSelectTable("inventory", "gbasedbt", "orders"));
        assertEquals("SELECT COUNT(1) FROM inventory:gbasedbt.orders",
                builder.dql().buildSelectCount("inventory", "gbasedbt", "orders"));
        assertEquals("SELECT * FROM inventory:orders",
                builder.dql().buildSelectTable("inventory", null, "orders"));
        assertEquals("SELECT * FROM gbasedbt.orders",
                builder.dql().buildSelectTable(null, "gbasedbt", "orders"));
        assertEquals("SELECT * FROM orders",
                builder.dql().buildSelectTable(null, null, "orders"));
    }

    @Test
    void insertUsesDatabaseColonAndOwnerDot() {
        SingleInsertSqlRequest request = SingleInsertSqlRequest.builder()
                .databaseName("inventory")
                .schemaName("gbasedbt")
                .tableName("orders")
                .columnList(List.of("id", "name"))
                .valueList(List.of("1", "'Ada'"))
                .build();

        assertEquals("INSERT INTO inventory:gbasedbt.orders (id,name)  VALUES (1,'Ada')",
                builder.dml().buildInsert(request));
    }

    @Test
    void updateUsesDatabaseColonAndOwnerDot() {
        UpdateSqlRequest request = UpdateSqlRequest.builder()
                .databaseName("inventory")
                .schemaName("gbasedbt")
                .tableName("orders")
                .row(Map.of("status", "'PAID'"))
                .primaryKeyMap(Map.of("id", "1"))
                .build();

        assertEquals("UPDATE inventory:gbasedbt.orders SET status = 'PAID' WHERE id = 1",
                builder.dml().buildUpdate(request));
    }

    @Test
    void inheritedTableDdlUsesTheSameQualification() {
        assertEquals("DROP TABLE inventory:gbasedbt.orders",
                builder.ddl().table().buildDropTable(new DropTableRequest("inventory", "gbasedbt", "orders")));
        assertEquals("TRUNCATE TABLE inventory:gbasedbt.orders",
                builder.ddl().table().buildTruncateTable(
                        new TruncateTableRequest("inventory", "gbasedbt", "orders")));
    }
}
