package ai.chat2db.spi.util;

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
}
