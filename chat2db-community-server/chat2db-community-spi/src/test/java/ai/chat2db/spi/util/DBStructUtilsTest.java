package ai.chat2db.spi.util;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DBStructUtilsTest {

    @Test
    void generateCreateTableSQLAllowsMissingIntegerMetadata() {
        TableColumn column = new TableColumn();
        column.setName("id");
        column.setColumnType("INT");

        String sql = DBStructUtils.generateCreateTableSQL("users", List.of(column));

        assertEquals("""
                CREATE TABLE users (
                \tid INT
                );""", sql);
    }

    @Test
    void generateCreateTableSQLSkipsVarcharSizeWhenMetadataIsMissing() {
        TableColumn column = new TableColumn();
        column.setName("name");
        column.setColumnType("VARCHAR");
        column.setNullable(1);

        String sql = DBStructUtils.generateCreateTableSQL("users", List.of(column));

        assertEquals("""
                CREATE TABLE users (
                \tname VARCHAR
                );""", sql);
    }

    @Test
    void generateCreateTableSQLSkipsDecimalPrecisionWhenMetadataIsMissing() {
        TableColumn column = new TableColumn();
        column.setName("amount");
        column.setColumnType("DECIMAL");
        column.setNullable(0);

        String sql = DBStructUtils.generateCreateTableSQL("orders", List.of(column));

        assertEquals("""
                CREATE TABLE orders (
                \tamount DECIMAL NOT NULL
                );""", sql);
    }

    @Test
    void generateCreateTableSQLAllowsDecimalPrecisionWithoutScale() {
        TableColumn column = new TableColumn();
        column.setName("amount");
        column.setColumnType("DECIMAL");
        column.setColumnSize(12);

        String sql = DBStructUtils.generateCreateTableSQL("orders", List.of(column));

        assertEquals("""
                CREATE TABLE orders (
                \tamount DECIMAL(12)
                );""", sql);
    }

    @Test
    void generateCreateTableSQLToleratesNullColumnType() {
        TableColumn column = new TableColumn();
        column.setName("expr");
        column.setColumnType(null);
        column.setColumnSize(10);

        assertEquals("""
                CREATE TABLE users (
                \texpr VARCHAR(10)
                );""", DBStructUtils.generateCreateTableSQL("users", List.of(column)));
    }

    @Test
    void generateCreateTableSQLEscapesCommentQuotesWithoutChangingBackslashes() {
        TableColumn column = new TableColumn();
        column.setName("name");
        column.setColumnType("VARCHAR");
        column.setComment("O'Brien\\docs");

        String sql = DBStructUtils.generateCreateTableSQL("users", List.of(column));

        assertEquals("""
                CREATE TABLE users (
                \tname VARCHAR COMMENT 'O''Brien\\docs'
                );""", sql);
    }

    @Test
    void buildAlterTableUsesNullToRemoveTableComment() {
        Table oldTable = table("existing");
        Table newTable = table(null);

        assertEquals("COMMENT ON TABLE users IS NULL;\n", DBStructUtils.buildAlterTable(oldTable, newTable));
    }

    @Test
    void buildAlterTableEscapesTableCommentQuotesWithoutChangingBackslashes() {
        Table oldTable = table("existing");
        Table newTable = table("O'Brien\\docs");

        assertEquals("COMMENT ON TABLE users IS 'O''Brien\\docs';\n",
                DBStructUtils.buildAlterTable(oldTable, newTable));
    }

    private static Table table(String comment) {
        Table table = new Table();
        table.setName("users");
        table.setComment(comment);
        table.setColumnList(List.of());
        table.setIndexList(List.of());
        return table;
    }
}
