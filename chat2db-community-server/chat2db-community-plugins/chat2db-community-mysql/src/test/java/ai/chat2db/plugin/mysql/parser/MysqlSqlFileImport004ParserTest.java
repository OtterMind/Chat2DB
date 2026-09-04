package ai.chat2db.plugin.mysql.parser;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlSqlFileImport004ParserTest {

    @Test
    void mysqlImport004FixtureKeepsDelimiterRoutinesTriggersAndDmlRepeatable() {
        File fixture = fixture("fixtures/MYSQL-IMPORT-004.sql");
        List<String> statements = new ArrayList<>();
        AtomicBoolean flushed = new AtomicBoolean();

        int count = new MysqlSqlParser().parserSqlScript(fixture, (bytesRead, statementsParsed) -> {},
                collectingHandler(statements, flushed), StandardCharsets.UTF_8);

        assertEquals(7, count);
        assertTrue(flushed.get());
        assertTrue(statements.stream().anyMatch(sql -> sql.contains("CREATE PROCEDURE import_004_routine()")));
        assertTrue(statements.stream().anyMatch(sql -> sql.contains("CREATE TRIGGER import_004_trigger")));
        assertTrue(statements.stream().noneMatch(sql -> sql.contains("CREATE PROCEDURE")
                && sql.contains("CREATE TRIGGER")));
        assertTrue(statements.contains("INSERT INTO orders(id, name) VALUES (2, 'insert')"));
        assertTrue(statements.contains("UPDATE orders SET name = 'updated' WHERE id = 2"));
        assertTrue(statements.contains("DELETE FROM orders WHERE id = 3"));
        assertTrue(statements.contains("REPLACE INTO orders(id, name) VALUES (4, 'replace')"));
    }

    @Test
    void parserSqlScriptHonorsRequestedCharset() throws Exception {
        Path file = Files.createTempFile("mysql-import-004-charset", ".sql");
        Files.writeString(file, "INSERT INTO charset_check(name) VALUES ('中文');",
                Charset.forName("GB18030"));
        List<String> statements = new ArrayList<>();

        int count = new MysqlSqlParser().parserSqlScript(file.toFile(), (bytesRead, statementsParsed) -> {},
                collectingHandler(statements, new AtomicBoolean()), Charset.forName("GB18030"));

        assertEquals(1, count);
        assertEquals("INSERT INTO charset_check(name) VALUES ('中文')", statements.get(0));
    }

    @Test
    void delimiterRemainsActiveForConsecutiveStoredObjects() throws Exception {
        Path file = Files.createTempFile("mysql-import-004-delimiter", ".sql");
        Files.writeString(file, consecutiveStoredObjectsSql());
        List<String> statements = new ArrayList<>();

        int count = new MysqlSqlParser().parserSqlScript(file.toFile(), (bytesRead, statementsParsed) -> {},
                collectingHandler(statements, new AtomicBoolean()), StandardCharsets.UTF_8);

        assertEquals(4, count, statements.toString());
        assertEquals(4, statements.size());
        assertTrue(statements.get(1).startsWith("CREATE PROCEDURE import004_insert"));
        assertTrue(statements.get(2).startsWith("CREATE TRIGGER import004_before_insert"));
        assertEquals("CALL import004_insert(1)", statements.get(3));
    }

    @Test
    void stringParserSeparatesConsecutiveStoredObjectsUsingOneDelimiterDirective() {
        List<Statement> statements = new MysqlSqlParser().parserSqlScript(consecutiveStoredObjectsSql());

        assertEquals(4, statements.size());
        assertTrue(statements.get(1).getSql().startsWith("CREATE PROCEDURE import004_insert"));
        assertTrue(statements.get(2).getSql().startsWith("CREATE TRIGGER import004_before_insert"));
        assertEquals("CALL import004_insert(1)", statements.get(3).getSql());
    }

    private String consecutiveStoredObjectsSql() {
        return """
                USE chat2db_import004;
                DELIMITER $$
                CREATE PROCEDURE import004_insert(IN p_id BIGINT)
                BEGIN
                    INSERT INTO import004_innodb(id) VALUES (p_id);
                END$$
                CREATE TRIGGER import004_before_insert
                BEFORE INSERT ON import004_innodb
                FOR EACH ROW
                BEGIN
                    SET NEW.id = NEW.id;
                END$$
                DELIMITER ;
                CALL import004_insert(1);
                """;
    }

    private ISqlBatchHandler collectingHandler(List<String> statements, AtomicBoolean flushed) {
        return new ISqlBatchHandler() {
            @Override
            public void handle(Statement statement) {
                statements.add(statement.getSql());
            }

            @Override
            public void flush() {
                flushed.set(true);
            }
        };
    }

    private File fixture(String name) {
        URL resource = getClass().getClassLoader().getResource(name);
        if (resource == null) {
            throw new IllegalStateException("Missing test fixture: " + name);
        }
        return new File(resource.getFile());
    }
}
