package ai.chat2db.community.sqlx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlxConnectionMappingTest {

    private static WorkspaceDataSource mysql() {
        WorkspaceDataSource source = new WorkspaceDataSource();
        source.setId(7L);
        source.setAlias("prod-mysql");
        source.setType("MYSQL");
        source.setHost("db.example.com");
        source.setPort("3307");
        source.setUser("reader");
        source.setPassword("secret");
        source.setUrl("jdbc:mysql://db.example.com:3307/app?useSSL=false");
        source.setServiceName("app");
        return source;
    }

    @Test
    void mapsTheSupportedEngines() {
        assertEquals("mysql", SqlxConnectionMapping.engine("MYSQL"));
        assertEquals("postgresql", SqlxConnectionMapping.engine("PostgreSQL"));
        assertEquals("oceanbase", SqlxConnectionMapping.engine("OCEANBASE_ORACLE"));
        assertEquals("opengauss", SqlxConnectionMapping.engine("GAUSSDB"));
        assertEquals("dameng", SqlxConnectionMapping.engine("DM"));
        assertEquals("kingbase", SqlxConnectionMapping.engine("KINGBASE"));
        assertEquals("h2", SqlxConnectionMapping.engine("H2"));
        assertEquals("duckdb", SqlxConnectionMapping.engine("DUCKDB"));
    }

    @Test
    void mapsTheEnginesAddedAfterH2() {
        assertEquals("presto", SqlxConnectionMapping.engine("PRESTO"));
        assertEquals("hive", SqlxConnectionMapping.engine("HIVE"));
        assertEquals("kylin", SqlxConnectionMapping.engine("KYLIN"));
        assertEquals("xugu", SqlxConnectionMapping.engine("XUGUDB"));
        assertEquals("db2", SqlxConnectionMapping.engine("DB2"));
        assertEquals("informix", SqlxConnectionMapping.engine("INFOMIX"));
        assertEquals("sundb", SqlxConnectionMapping.engine("SUNDB"));
        assertEquals("gbase8s", SqlxConnectionMapping.engine("GBASE8S"));
    }

    @Test
    void leavesTheEnginesSqlxCannotReach() {
        for (String unavailable : java.util.List.of("SNOWFLAKE", "OSCAR", "ELASTICSEARCH", "BIGQUERY",
                "REDSHIFT")) {
            assertNull(SqlxConnectionMapping.engine(unavailable), unavailable);
        }
    }

    @Test
    void enginesSqlxDoesNotSpeakAreSkippedWithAReason() {
        for (String type : List.of("SNOWFLAKE", "OSCAR", "ELASTICSEARCH", "BIGQUERY", "REDSHIFT", "DEFAULT")) {
            assertEquals(null, SqlxConnectionMapping.engine(type), type);
        }
        WorkspaceDataSource snohflake = mysql();
        snohflake.setType("SNOWFLAKE");
        SqlxConnectionMapping.Mapped mapped = SqlxConnectionMapping.map(snohflake);
        assertFalse(mapped.supported());
        assertEquals(SqlxConnectionMapping.REASON_ENGINE, mapped.reason());
    }

    @Test
    void mapsFieldsOfANetworkDatasource() {
        SqlxConnectionMapping.Mapped mapped = SqlxConnectionMapping.map(mysql());
        assertTrue(mapped.supported());
        Map<String, Object> connection = mapped.connection();
        assertEquals("mysql", connection.get("database_type"));
        assertEquals("db.example.com", connection.get("host"));
        assertEquals(3307, connection.get("port"));
        assertEquals("app", connection.get("database"));
        assertEquals("reader", connection.get("username"));
        assertEquals("secret", connection.get("password"));
        // The saved URL disables TLS, so the mapped connection does too.
        assertEquals("disable", connection.get("tls"));
        // A datasource without a hint keeps TLS off: Chat2DB itself connects permissively.
        WorkspaceDataSource plain = mysql();
        plain.setUrl("jdbc:mysql://db.example.com:3307/app");
        assertEquals("disable", SqlxConnectionMapping.tls(plain.getUrl()));
        assertEquals("verify-full", SqlxConnectionMapping.tls("jdbc:postgresql://db:5432/app?sslmode=require"));
    }

    @Test
    void fileEnginesCarryAPathInsteadOfAHost() {
        WorkspaceDataSource sqlite = new WorkspaceDataSource();
        sqlite.setAlias("local-sqlite");
        sqlite.setType("SQLITE");
        sqlite.setUrl("jdbc:sqlite:/Users/dev/data/app.db");
        SqlxConnectionMapping.Mapped mapped = SqlxConnectionMapping.map(sqlite);
        assertTrue(mapped.supported());
        assertEquals("/Users/dev/data/app.db", mapped.connection().get("database"));
        assertEquals("", mapped.connection().get("host"));
        assertEquals(0, mapped.connection().get("port"));

        WorkspaceDataSource duckdb = new WorkspaceDataSource();
        duckdb.setType("DUCKDB");
        duckdb.setUrl("jdbc:duckdb:file:/tmp/analytics.duckdb");
        assertEquals("/tmp/analytics.duckdb", SqlxConnectionMapping.map(duckdb).connection().get("database"));

        WorkspaceDataSource noPath = new WorkspaceDataSource();
        noPath.setType("SQLITE");
        SqlxConnectionMapping.Mapped broken = SqlxConnectionMapping.map(noPath);
        assertFalse(broken.supported());
        assertEquals(SqlxConnectionMapping.REASON_PATH, broken.reason());
    }

    @Test
    void embeddedH2OpensAFileWhileTcpH2StaysRemote() {
        WorkspaceDataSource embedded = new WorkspaceDataSource();
        embedded.setType("H2");
        embedded.setUrl("jdbc:h2:file:/tmp/chat2db/db/demo");
        SqlxConnectionMapping.Mapped local = SqlxConnectionMapping.map(embedded);
        assertTrue(local.supported());
        assertEquals("/tmp/chat2db/db/demo", local.connection().get("database"));
        assertEquals(0, local.connection().get("port"));

        WorkspaceDataSource server = new WorkspaceDataSource();
        server.setType("H2");
        server.setHost("127.0.0.1");
        server.setPort("9092");
        server.setUrl("jdbc:h2:tcp://127.0.0.1:9092/demo");
        SqlxConnectionMapping.Mapped remote = SqlxConnectionMapping.map(server);
        assertTrue(remote.supported());
        assertEquals("127.0.0.1", remote.connection().get("host"));
        assertEquals(9092, remote.connection().get("port"));
        assertEquals("demo", remote.connection().get("database"));
    }

    @Test
    void incompleteConnectionsAreSkippedWithAReason() {
        WorkspaceDataSource noHost = mysql();
        noHost.setHost("");
        noHost.setUrl("");
        assertEquals(SqlxConnectionMapping.REASON_HOST, SqlxConnectionMapping.map(noHost).reason());

        WorkspaceDataSource badPort = mysql();
        badPort.setPort("not-a-port");
        assertEquals(SqlxConnectionMapping.REASON_PORT, SqlxConnectionMapping.map(badPort).reason());

        WorkspaceDataSource zeroPort = mysql();
        zeroPort.setPort("0");
        assertEquals(SqlxConnectionMapping.REASON_PORT, SqlxConnectionMapping.map(zeroPort).reason());
    }

    @Test
    void namesSurviveTheSqlxNamingRules() {
        assertEquals("prod-mysql", SqlxConnectionMapping.negotiateName(mysql()));
        WorkspaceDataSource unnamed = mysql();
        unnamed.setAlias("  ");
        assertEquals("mysql-db.example.com", SqlxConnectionMapping.negotiateName(unnamed));
        WorkspaceDataSource uuid = mysql();
        uuid.setAlias("6f1d0f0e-8c58-4a5f-9d34-6a5a4f3b2c11");
        assertEquals("datasource-6f1d0f0e-8c58-4a5f-9d34-6a5a4f3b2c11",
                SqlxConnectionMapping.negotiateName(uuid));
    }

    @Test
    void buildsTheVersionedDocumentAndSeparatesSkippedEntries() {
        WorkspaceDataSource hive = mysql();
        hive.setAlias("legacy-hive");
        hive.setType("SNOWFLAKE");
        SqlxConnectionMapping.Document document =
                SqlxConnectionMapping.buildDocument(List.of(mysql(), hive));
        assertEquals(2, document.candidates());
        assertEquals(1, document.entries());
        assertEquals(1, document.skipped().size());
        assertEquals("legacy-hive", document.skipped().get(0).get("name"));
        assertEquals(SqlxConnectionMapping.REASON_ENGINE, document.skipped().get(0).get("reason"));
        assertTrue(document.json().contains("\"version\":1"), document.json());
        assertTrue(document.json().contains("\"mode\":\"merge\""), document.json());
        assertTrue(document.json().contains("\"name\":\"prod-mysql\""), document.json());
        assertFalse(document.json().contains("legacy-hive"), document.json());
    }

    @Test
    void recognisesAConnectionSqlxAlreadyHolds() {
        Map<String, Object> stored = SqlxConnectionMapping.map(mysql()).connection();
        // The CLI prints only these fields back, so the page compares them to spot an imported row.
        assertTrue(SqlxConnectionMapping.sameTarget(stored, SqlxConnectionMapping.map(mysql()).connection()));
        assertTrue(SqlxConnectionMapping.sameTarget(
                Map.of("database_type", "mysql", "host", " db.example.com ", "port", 3307,
                        "database", "app", "service", "", "tls", "verify-full"),
                SqlxConnectionMapping.map(mysql()).connection()));

        assertFalse(SqlxConnectionMapping.sameTarget(stored,
                SqlxConnectionMapping.map(withPort(mysql(), "3308")).connection()));
        assertFalse(SqlxConnectionMapping.sameTarget(stored,
                SqlxConnectionMapping.map(withType(mysql(), "POSTGRESQL")).connection()));
        assertFalse(SqlxConnectionMapping.sameTarget(null, stored));
    }

    private static WorkspaceDataSource withPort(WorkspaceDataSource source, String port) {
        source.setPort(port);
        source.setUrl("jdbc:mysql://db.example.com:" + port + "/app?useSSL=false");
        return source;
    }

    private static WorkspaceDataSource withType(WorkspaceDataSource source, String type) {
        source.setType(type);
        return source;
    }
}
