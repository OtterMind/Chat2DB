package ai.chat2db.plugin.cockroachdb.builder;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import ai.chat2db.plugin.cockroachdb.CockroachDBPlugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Requires a disposable CockroachDB database selected explicitly by the test runner. */
@EnabledIfEnvironmentVariable(named = "COCKROACH_TEST_URL", matches = ".+")
class CockroachDBSingleRowNativeTest {
    @Test
    void duplicateRowsWithoutExplicitKeyAreLimitedToOne() throws Exception {
        verify("id", false);
    }

    @Test
    void nonUniqueBusinessRowidCannotWidenTheMutation() throws Exception {
        verify("rowid", false);
    }

    @Test
    void nullBusinessRowidStillUpdatesAndDeletesOneRow() throws Exception {
        verify("rowid", true);
    }

    private void verify(String column, boolean nullValue) throws Exception {
        String table = "pr2484_" + UUID.randomUUID().toString().replace("-", "");
        String value = nullValue ? null : "7";
        String literal = nullValue ? "NULL" : "7";
        IPlugin previous = Chat2DBContext.PLUGIN_MAP.put("COCKROACHDB", new CockroachDBPlugin());
        try (Connection connection = DriverManager.getConnection(System.getenv("COCKROACH_TEST_URL"), "root", "");
             Statement statement = connection.createStatement()) {
            ConnectInfo info = new ConnectInfo();
            info.setDbType("COCKROACHDB"); info.setDriverConfig(new DriverConfig()); info.setConnection(connection);
            Chat2DBContext.putContext(info);
            statement.execute("CREATE TABLE " + table + " (" + column + " INT, qty INT)");
            try {
                statement.execute("INSERT INTO " + table + " VALUES(" + literal + ",10),(" + literal + ",10),(" + literal + ",20)");
                QueryResponse query = new QueryResponse();
                query.setTableName(table);
                query.setHeaderList(List.of(
                        Header.builder().name("_selector").primaryKey(false).columnType("text").build(),
                        Header.builder().name(column).primaryKey(false).columnType("int8").build(),
                        Header.builder().name("qty").primaryKey(false).columnType("int8").build()));
                ResultOperation update = new ResultOperation();
                update.setType("UPDATE"); update.setOldDataList(Arrays.asList("", value, "10"));
                update.setDataList(Arrays.asList("", value, "99")); query.setOperations(List.of(update));
                String updateSql = new CockroachDBSqlBuilder().buildByQueryResult(query);
                assertEquals(1, statement.executeUpdate(updateSql), updateSql);
                assertCounts(statement, table, 1, 1, 1);
                ResultOperation delete = new ResultOperation();
                delete.setType("DELETE"); delete.setOldDataList(Arrays.asList("", value, "10"));
                query.setOperations(List.of(delete));
                String deleteSql = new CockroachDBSqlBuilder().buildByQueryResult(query);
                assertEquals(1, statement.executeUpdate(deleteSql), deleteSql);
                assertCounts(statement, table, 0, 1, 1);
                assertEquals(0, statement.executeUpdate(deleteSql));
                statement.execute("INSERT INTO " + table + " VALUES(" + literal + ",10),(" + literal + ",10)");
                assertEquals(1, statement.executeUpdate(deleteSql), "Exactly one duplicate must be deleted");
                assertCounts(statement, table, 1, 1, 1);
            } finally {
                statement.execute("DROP TABLE " + table);
                info.setConnection(null);
            }
        } finally {
            Chat2DBContext.removeContext();
            if (previous == null) Chat2DBContext.PLUGIN_MAP.remove("COCKROACHDB");
            else Chat2DBContext.PLUGIN_MAP.put("COCKROACHDB", previous);
        }
    }

    private void assertCounts(Statement statement, String table, int tens, int twenties, int changed) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT count(*) FILTER (WHERE qty=10),"
                + "count(*) FILTER (WHERE qty=20),count(*) FILTER (WHERE qty=99) FROM " + table)) {
            assertTrue(rows.next());
            assertEquals(tens, rows.getInt(1)); assertEquals(twenties, rows.getInt(2)); assertEquals(changed, rows.getInt(3));
        }
    }
}
