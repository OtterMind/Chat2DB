import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.plugin.bigquery.parser.BigQueryParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class BigQueryParserTest {

    private final BigQueryParser parser = new BigQueryParser();

    @TempDir
    Path tempDirectory;

    @Test
    void splitsValidBigQueryNativeStatements() {
        String merge = """
                MERGE `project.dataset.target` AS target
                USING `project.dataset.source` AS source
                ON target.id = source.id
                WHEN MATCHED THEN UPDATE SET name = source.name
                WHEN NOT MATCHED THEN INSERT (id, name) VALUES (source.id, source.name)
                """.strip();
        String declare = "DECLARE run_date DATE DEFAULT CURRENT_DATE()";
        String export = """
                EXPORT DATA OPTIONS(
                  uri='gs://bucket/export-*.csv',
                  format='CSV',
                  overwrite=true)
                AS SELECT * FROM `project.dataset.events`
                """.strip();

        List<Statement> statements = parser.parserSqlScript(
                merge + ";\n" + declare + ";\n" + export + ";");

        Assertions.assertEquals(List.of(merge, declare, export), sqlOf(statements));
    }

    @Test
    void ignoresSemicolonsInsideBigQueryQuotesAndComments() {
        String script = "SELECT 'single;quote', \"double;quote\", "
                + "'''triple;single''', \"\"\"triple;double\"\"\";\n"
                + "SELECT * FROM `project;name.dataset.table`;\n"
                + "SELECT 1 /* block; comment */;\n"
                + "-- leading; comment\n"
                + "SELECT 2 # trailing; comment\n;";
        List<Statement> statements = parser.parserSqlScript(script);

        Assertions.assertEquals(4, statements.size());
        Assertions.assertTrue(statements.get(0).getSql().contains("'''triple;single'''"));
        Assertions.assertTrue(statements.get(0).getSql().contains("\"\"\"triple;double\"\"\""));
        Assertions.assertTrue(statements.get(1).getSql().contains("`project;name.dataset.table`"));
        Assertions.assertTrue(statements.get(2).getSql().contains("/* block; comment */"));
        Assertions.assertTrue(statements.get(3).getSql().contains("-- leading; comment"));
        Assertions.assertTrue(statements.get(3).getSql().contains("# trailing; comment"));
    }

    @Test
    void handlesEscapedAndDoubledQuoteCharacters() {
        String script = "SELECT 'it''s;still one', 'backslash\\';still one';\n"
                + "SELECT \"a \"\"quoted;value\"\"\";\n"
                + "SELECT 3;";
        List<Statement> statements = parser.parserSqlScript(script);

        Assertions.assertEquals(3, statements.size());
        Assertions.assertTrue(statements.get(0).getSql().contains("it''s;still one"));
        Assertions.assertTrue(statements.get(0).getSql().contains("backslash\\';still one"));
        Assertions.assertTrue(statements.get(1).getSql().contains("\"\"quoted;value\"\""));
    }

    @Test
    void doesNotEmitCommentOnlyFragments() {
        Assertions.assertTrue(parser.parserSqlScript("""
                -- comment; only
                /* another; comment */;
                # final; comment
                """).isEmpty());
    }

    @Test
    void streamsFileStatementsWithoutChangingTheirText() throws Exception {
        String merge = """
                MERGE `project.dataset.target` target
                USING `project.dataset.source` source
                ON target.id = source.id
                WHEN MATCHED THEN UPDATE SET payload = source.payload
                """.strip();
        String declare = "DECLARE message STRING DEFAULT '''\u4e2d\u6587;\uD83D\uDE00'''";
        String export = """
                EXPORT DATA OPTIONS(uri='gs://bucket/path;part-*.json', format='JSON')
                AS SELECT payload FROM `project.dataset.target`
                """.strip();
        String script = merge + ";\n" + declare + ";\n" + export;
        Path scriptFile = tempDirectory.resolve("bigquery.sql");
        Files.writeString(scriptFile, script, StandardCharsets.UTF_8);

        List<Statement> handledStatements = new ArrayList<>();
        List<Long> progressBytes = new ArrayList<>();
        List<Integer> progressCounts = new ArrayList<>();
        boolean[] flushed = {false};
        ISqlBatchHandler handler = new ISqlBatchHandler() {
            @Override
            public void handle(Statement statement) {
                handledStatements.add(statement);
            }

            @Override
            public void flush() {
                flushed[0] = true;
            }
        };

        int statementCount = parser.parserSqlScript(
                new File(scriptFile.toUri()),
                (bytesRead, statementsParsed) -> {
                    progressBytes.add(bytesRead);
                    progressCounts.add(statementsParsed);
                },
                handler);

        Assertions.assertEquals(3, statementCount);
        Assertions.assertEquals(List.of(merge, declare, export), sqlOf(handledStatements));
        Assertions.assertEquals(List.of(1, 2, 3), progressCounts);
        Assertions.assertEquals((long) script.getBytes(StandardCharsets.UTF_8).length,
                progressBytes.get(progressBytes.size() - 1));
        Assertions.assertTrue(flushed[0]);
    }

    private static List<String> sqlOf(List<Statement> statements) {
        return statements.stream().map(Statement::getSql).toList();
    }
}
