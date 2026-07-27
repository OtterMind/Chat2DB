package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.spi.util.SqlUtils;
import com.alibaba.druid.DbType;
import liquibase.change.AddColumnConfig;
import liquibase.change.Change;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.core.H2Database;
import liquibase.database.core.MySQLDatabase;
import liquibase.serializer.core.xml.XMLChangeLogSerializer;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Chat2dbDiffToChangeLogTest {

    @Test
    void normalizesScalarAndMultiValuedMysqlIndexExpressions() {
        CreateIndexChange createIndex = createIndex(
                computed("cast(json_extract(`payload`,_utf8mb4\\'$.provinceId\\') as unsigned)"),
                computed("cast(json_extract(`region_ids`,_latin1\\'$[*]\\') as unsigned array)"),
                computed("lower(`name`)"),
                computed("(json_extract(`payload`,_utf8mb4'$.alreadyValid'))"));

        List<ChangeSet> changeSets = List.of(changeSet(createIndex));
        Chat2dbDiffToChangeLog.repairMySqlComputedIndexExpressions(changeSets, new MySQLDatabase());
        Chat2dbDiffToChangeLog.repairMySqlComputedIndexExpressions(changeSets, new MySQLDatabase());

        assertEquals("(cast(json_extract(`payload`,_utf8mb4'$.provinceId') as unsigned))",
                createIndex.getColumns().get(0).getName());
        assertEquals("(cast(json_extract(`region_ids`,_latin1'$[*]') as unsigned array))",
                createIndex.getColumns().get(1).getName());
        assertEquals("(lower(`name`))", createIndex.getColumns().get(2).getName());
        assertEquals("(json_extract(`payload`,_utf8mb4'$.alreadyValid'))",
                createIndex.getColumns().get(3).getName());
    }

    @Test
    void leavesOrdinaryColumnsRawSqlAndNonMysqlChangesUntouched() {
        AddColumnConfig ordinaryColumn = column("_utf8mb4\\'notAnExpression\\'", false);
        CreateIndexChange mysqlIndex = createIndex(ordinaryColumn);
        RawSQLChange rawSql = new RawSQLChange("select _utf8mb4\\'ordinarySql\\'");

        Chat2dbDiffToChangeLog.repairMySqlComputedIndexExpressions(
                List.of(changeSet(mysqlIndex, rawSql)), new MySQLDatabase());

        assertEquals("_utf8mb4\\'notAnExpression\\'", ordinaryColumn.getName());
        assertEquals("select _utf8mb4\\'ordinarySql\\'", rawSql.getSql());

        AddColumnConfig h2ComputedColumn = computed("_utf8mb4\\'$.regionId\\'");
        Chat2dbDiffToChangeLog.repairMySqlComputedIndexExpressions(
                List.of(changeSet(createIndex(h2ComputedColumn))), new H2Database());

        assertEquals("_utf8mb4\\'$.regionId\\'", h2ComputedColumn.getName());
    }

    @Test
    void serializesValidXmlAndProducesSqlThatDruidCanParse() throws Exception {
        AddColumnConfig column = computed(
                "cast(json_extract(`payload`,_utf8mb4\\'$.provinceId\\') as unsigned)");
        ChangeSet changeSet = changeSet(createIndex(column));

        Chat2dbDiffToChangeLog.repairMySqlComputedIndexExpressions(
                List.of(changeSet), new MySQLDatabase());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XMLChangeLogSerializer().write(List.of(changeSet), new PrintStream(output, true, StandardCharsets.UTF_8));
        String xml = output.toString(StandardCharsets.UTF_8);

        assertTrue(xml.contains("(cast(json_extract(`payload`,_utf8mb4'$.provinceId') as unsigned))"));
        assertFalse(xml.contains("_utf8mb4\\'$.provinceId\\'"));

        MySQLDatabase database = new MySQLDatabase();
        String sql = SqlGeneratorFactory.getInstance()
                .generateSql(changeSet.getChanges().get(0), database)[0].toSql();
        assertTrue(sql.contains("((cast(json_extract(`payload`,_utf8mb4'$.provinceId') as unsigned)))"));
        assertDoesNotThrow(() -> SqlUtils.parse(sql, DbType.mysql, true));

        CreateIndexChange multiValuedIndex = createIndex(
                computed("cast(json_extract(`region_ids`,_latin1\\'$[*]\\') as unsigned array)"));
        Chat2dbDiffToChangeLog.repairMySqlComputedIndexExpressions(
                List.of(changeSet(multiValuedIndex)), database);
        String multiValuedSql = SqlGeneratorFactory.getInstance()
                .generateSql(multiValuedIndex, database)[0].toSql();
        assertTrue(multiValuedSql.contains(
                "((cast(json_extract(`region_ids`,_latin1'$[*]') as unsigned array)))"));
        assertDoesNotThrow(() -> SqlUtils.parse(multiValuedSql, DbType.mysql, true));
    }

    private static AddColumnConfig computed(String expression) {
        return column(expression, true);
    }

    private static AddColumnConfig column(String name, boolean computed) {
        AddColumnConfig column = new AddColumnConfig();
        column.setName(name);
        column.setComputed(computed);
        return column;
    }

    private static CreateIndexChange createIndex(AddColumnConfig... columns) {
        CreateIndexChange createIndex = new CreateIndexChange();
        createIndex.setIndexName("idx_payload_region");
        createIndex.setTableName("sample_config");
        for (AddColumnConfig column : columns) {
            createIndex.addColumn(column);
        }
        return createIndex;
    }

    private static ChangeSet changeSet(Change... changes) {
        ChangeSet changeSet = new ChangeSet("1", "test", false, false,
                "test.xml", null, null, new DatabaseChangeLog());
        for (Change change : changes) {
            changeSet.addChange(change);
        }
        return changeSet;
    }
}
