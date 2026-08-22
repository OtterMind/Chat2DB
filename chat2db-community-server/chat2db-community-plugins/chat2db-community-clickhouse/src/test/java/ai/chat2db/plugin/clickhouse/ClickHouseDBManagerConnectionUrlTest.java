package ai.chat2db.plugin.clickhouse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClickHouseDBManagerConnectionUrlTest {

    @Test
    void replacesDatabasePathForBracketedIpv6AndPreservesQueryAndFragment() {
        assertEquals(
                "jdbc:clickhouse://[2001:db8::1]:8123/analytics?ssl=true#replica/tag",
                ClickHouseDBManager.replaceDatabaseInJdbcUrl(
                        "jdbc:clickhouse://[2001:db8::1]:8123/default?ssl=true#replica/tag",
                        "analytics")
        );
    }

    @Test
    void repeatedReplacementIsIdempotent() {
        String expected = "jdbc:clickhouse://[2001:db8::1]:8123/analytics?ssl=true";
        String first = ClickHouseDBManager.replaceDatabaseInJdbcUrl(
                "jdbc:clickhouse://[2001:db8::1]:8123/default?ssl=true",
                "analytics");

        assertEquals(expected, first);
        assertEquals(expected, ClickHouseDBManager.replaceDatabaseInJdbcUrl(first, "analytics"));
    }

    @Test
    void appendsDatabasePathWhenHostnameUrlHasNoPath() {
        assertEquals(
                "jdbc:clickhouse://clickhouse.example:8123/analytics",
                ClickHouseDBManager.replaceDatabaseInJdbcUrl(
                        "jdbc:clickhouse://clickhouse.example:8123",
                        "analytics")
        );
    }

    @Test
    void appendsDatabasePathBeforeFragmentWhenUrlHasNoPath() {
        assertEquals(
                "jdbc:clickhouse://clickhouse.example:8123/analytics#replica/tag",
                ClickHouseDBManager.replaceDatabaseInJdbcUrl(
                        "jdbc:clickhouse://clickhouse.example:8123#replica/tag",
                        "analytics")
        );
    }

    @Test
    void replacesExistingDatabasePathAndPreservesFragment() {
        assertEquals(
                "jdbc:clickhouse://clickhouse.example:8123/analytics#replica/tag",
                ClickHouseDBManager.replaceDatabaseInJdbcUrl(
                        "jdbc:clickhouse://clickhouse.example:8123/default#replica/tag",
                        "analytics")
        );
    }
}
