package ai.chat2db.plugin.clickhouse;

import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClickHouseDBManagerConnectionUrlEncodingTest {

    @Test
    void encodesReservedUrlDelimitersInDatabaseName() throws Exception {
        assertEquals(
                "jdbc:clickhouse://localhost:8123/analytics%2Feast%3Fmode%23blue?ssl=true",
                rewriteDatabase("analytics/east?mode#blue")
        );
    }

    @Test
    void encodesSpacesAndUnicodeInDatabaseName() throws Exception {
        assertEquals(
                "jdbc:clickhouse://localhost:8123/%E5%88%86%E6%9E%90%20%E5%BA%93?ssl=true",
                rewriteDatabase("分析 库")
        );
    }

    @Test
    void encodesLiteralPercentAndPlusInDatabaseName() throws Exception {
        assertEquals(
                "jdbc:clickhouse://localhost:8123/already%252F%2Bname?ssl=true",
                rewriteDatabase("already%2F+name")
        );
    }

    @Test
    void encodesDatabaseSegmentWhilePreservingIpv6AuthorityQueryAndFragment() {
        assertEquals(
                "jdbc:clickhouse://[2001:db8::1]:8123/analytics%2Feast%3Fmode%23blue?ssl=true#replica/tag",
                ClickHouseDBManager.replaceDatabaseInJdbcUrl(
                        "jdbc:clickhouse://[2001:db8::1]:8123/default?ssl=true#replica/tag",
                        "analytics/east?mode#blue")
        );
    }

    private static String rewriteDatabase(String databaseName) throws Exception {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setUrl("jdbc:clickhouse://localhost:8123/default?ssl=true");
        connectInfo.setHost("localhost");
        connectInfo.setPort(8123);
        connectInfo.setSchemaName(databaseName);

        Method method = ClickHouseDBManager.class.getDeclaredMethod("setDatabaseInJdbcUrl", ConnectInfo.class);
        method.setAccessible(true);
        return (String) method.invoke(new ClickHouseDBManager(), connectInfo);
    }
}
