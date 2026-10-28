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
    void keepsReservedKeywordsInPathExpressionsOutOfScriptBlockTracking() {
        Assertions.assertEquals(List.of(
                        "SELECT * FROM dataset.begin",
                        "SELECT * FROM dataset.case",
                        "SELECT * FROM dataset.end",
                        "SELECT 2"),
                sqlOf(parser.parserSqlScript("""
                        SELECT * FROM dataset.begin;
                        SELECT * FROM dataset.case;
                        SELECT * FROM dataset.end;
                        SELECT 2;
                        """)));
    }

    @Test
    void keepsReservedPathPartsInsideScriptBlocksFromClosingThem() {
        String block = """
                BEGIN
                  SELECT * FROM dataset.end;
                  SELECT * FROM dataset.begin;
                END
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 3"),
                sqlOf(parser.parserSqlScript(block + "; SELECT 3;")));
    }

    @Test
    void keepsReservedPartsInUnquotedSpecialPathIdentifiersOutOfScriptBlockTracking() {
        Assertions.assertEquals(List.of(
                        "SELECT * FROM project-begin",
                        "SELECT * FROM project-case",
                        "SELECT * FROM project-end",
                        "SELECT * FROM project/begin",
                        "SELECT * FROM project/case",
                        "SELECT * FROM project/end",
                        "SELECT * FROM project:begin",
                        "SELECT * FROM project:case",
                        "SELECT * FROM project:end",
                        "SELECT 2"),
                sqlOf(parser.parserSqlScript("""
                        SELECT * FROM project-begin;
                        SELECT * FROM project-case;
                        SELECT * FROM project-end;
                        SELECT * FROM project/begin;
                        SELECT * FROM project/case;
                        SELECT * FROM project/end;
                        SELECT * FROM project:begin;
                        SELECT * FROM project:case;
                        SELECT * FROM project:end;
                        SELECT 2;
                        """)));
    }

    @Test
    void keepsReservedSpecialPathPartsInsideScriptBlocksFromClosingThem() {
        String block = """
                BEGIN
                  SELECT * FROM project-end;
                  SELECT * FROM project-begin;
                  SELECT * FROM project/end;
                  SELECT * FROM project/begin;
                  SELECT * FROM project:end;
                  SELECT * FROM project:begin;
                END
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 3"),
                sqlOf(parser.parserSqlScript(block + "; SELECT 3;")));
    }

    @Test
    void keepsCaseExpressionsAfterArithmeticOperatorsInsideScriptBlocks() {
        String block = """
                BEGIN
                  SELECT 1 - CASE WHEN TRUE THEN 1 ELSE 0 END;
                  SELECT 8 / CASE WHEN TRUE THEN 2 ELSE 1 END;
                  SELECT 2;
                END
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 3"),
                sqlOf(parser.parserSqlScript(block + "; SELECT 3;")));
    }

    @Test
    void keepsNoWhitespaceCaseExpressionsAfterOperatorsInsideScriptBlocks() {
        String block = """
                BEGIN
                  SELECT 1-CASE WHEN TRUE THEN 1-CASE WHEN FALSE THEN 2 ELSE 3 END ELSE 0 END;
                  SELECT 8/CASE WHEN TRUE THEN 2 ELSE 1 END;
                END
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 3"),
                sqlOf(parser.parserSqlScript(block + "; SELECT 3;")));
    }

    @Test
    void keepsReservedPartsInTableTargetsAndSourceListsOutOfBlockTracking() {
        String quotedJoin = "SELECT * FROM `project.dataset.foo` JOIN project-begin ON TRUE";
        String tableList = "SELECT * FROM foo, project-begin";
        String create = "CREATE TABLE IF NOT EXISTS project-case (id INT64)";
        String drop = "DROP TABLE IF EXISTS project-end";
        String merge = """
                MERGE INTO project-begin target USING project-case source ON FALSE
                WHEN NOT MATCHED THEN INSERT (id) VALUES (1)
                """.strip();
        String insert = "INSERT project-begin (id) VALUES (1)";
        String delete = "DELETE project-end WHERE TRUE";
        String load = """
                LOAD DATA OVERWRITE project-case FROM FILES
                (format = 'CSV', uris = ['gs://bucket/input.csv'])
                """.strip();

        Assertions.assertEquals(List.of(
                        quotedJoin, tableList, create, drop, merge, insert, delete, load,
                        "SELECT 2"),
                sqlOf(parser.parserSqlScript(String.join(";\n",
                        quotedJoin, tableList, create, drop, merge, insert, delete, load, "SELECT 2")
                        + ";")));
    }

    @Test
    void keepsReservedNamedParametersOutOfScriptBlockTracking() {
        Assertions.assertEquals(List.of(
                        "SELECT @begin AS begin_value",
                        "SELECT @case AS case_value",
                        "SELECT @@end AS end_value",
                        "SELECT 2"),
                sqlOf(parser.parserSqlScript("""
                        SELECT @begin AS begin_value;
                        SELECT @case AS case_value;
                        SELECT @@end AS end_value;
                        SELECT 2;
                        """)));
    }

    @Test
    void keepsReservedNamedParametersInsideScriptBlocksFromClosingThem() {
        String block = """
                BEGIN
                  SELECT @end AS end_value;
                  SELECT @begin AS begin_value;
                END
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 3"),
                sqlOf(parser.parserSqlScript(block + "; SELECT 3;")));
    }

    @Test
    void keepsNestedBigQueryScriptingBlocksTogether() {
        String block = """
                BEGIN
                  DECLARE counter INT64 DEFAULT 0;
                  IF counter = 0 THEN
                    SET counter = 1;
                  END IF;
                  LOOP
                    SET counter = counter + 1;
                    BREAK;
                  END LOOP;
                  WHILE counter < 3 DO
                    SET counter = counter + 1;
                  END WHILE;
                  FOR item IN (SELECT 1 AS value) DO
                    SET counter = counter + item.value;
                  END FOR;
                  REPEAT
                    SET counter = counter - 1;
                  UNTIL counter = 0
                  END REPEAT;
                END
                """.strip();

        List<Statement> statements = parser.parserSqlScript(block + ";\nSELECT 1;");

        Assertions.assertEquals(List.of(block, "SELECT 1"), sqlOf(statements));
    }

    @Test
    void caseExpressionDoesNotCloseOuterBeginBlock() {
        String block = """
                BEGIN
                  SELECT CASE WHEN TRUE THEN 1 ELSE 0 END AS value;
                  SELECT 2;
                END
                """.strip();

        List<Statement> statements = parser.parserSqlScript(block + ";\nSELECT 3;");

        Assertions.assertEquals(List.of(block, "SELECT 3"), sqlOf(statements));
    }

    @Test
    void keepsCaseStatementAndLabeledBlocksTogether() {
        String block = """
                BEGIN
                  CASE
                    WHEN TRUE THEN SELECT 1;
                    ELSE SELECT 2;
                  END CASE;
                  outer_loop: LOOP
                    SELECT 3;
                    BREAK outer_loop;
                  END LOOP outer_loop;
                  guarded: WHILE FALSE DO
                    SELECT 4;
                  END WHILE guarded;
                  items: FOR item IN (SELECT 5 AS value) DO
                    SELECT item.value;
                  END FOR items;
                  retry: REPEAT
                    SELECT 6;
                  UNTIL TRUE
                  END REPEAT retry;
                END
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 7"),
                sqlOf(parser.parserSqlScript(block + ";\nSELECT 7;")));
    }

    @Test
    void keepsFirstLabeledLoopInsideBeginTogether() {
        String block = """
                BEGIN
                  first_loop: LOOP
                    SELECT 1;
                    BREAK first_loop;
                  END LOOP first_loop;
                  SELECT 2;
                END
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 3"),
                sqlOf(parser.parserSqlScript(block + ";\nSELECT 3;")));
    }

    @Test
    void keepsLabeledBeginBlockTogether() {
        String block = """
                outer_block: BEGIN
                  SELECT 1;
                END outer_block
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 2"),
                sqlOf(parser.parserSqlScript(block + ";\nSELECT 2;")));
    }

    @Test
    void keepsLabelsSeparatedFromColonsByCommentsTogether() {
        String loop = """
                outer /* block comment */ : LOOP
                  SELECT 1;
                  BREAK outer;
                END LOOP outer
                """.strip();
        String guarded = """
                guarded -- line comment
                : WHILE FALSE DO
                  SELECT 2;
                END WHILE guarded
                """.strip();
        String items = """
                items # hash comment
                : FOR item IN (SELECT 3 AS value) DO
                  SELECT item.value;
                END FOR items
                """.strip();

        Assertions.assertEquals(List.of(loop, guarded, items, "SELECT 4"),
                sqlOf(parser.parserSqlScript(loop + ";\n" + guarded + ";\n" + items + ";\nSELECT 4;")));
    }

    @Test
    void keepsNestedBlockInsideExceptionHandlerTogether() {
        String block = """
                BEGIN
                  SELECT ERROR('failure');
                EXCEPTION WHEN ERROR THEN
                  IF TRUE THEN
                    SELECT 1;
                  END IF;
                  SELECT 2;
                END
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 3"),
                sqlOf(parser.parserSqlScript(block + ";\nSELECT 3;")));
    }

    @Test
    void keepsLoopInsideExceptionHandlerTogether() {
        String block = """
                BEGIN
                  SELECT ERROR('failure');
                EXCEPTION WHEN ERROR THEN
                  LOOP
                    SELECT 1;
                    BREAK;
                  END LOOP;
                  SELECT 2;
                END
                """.strip();

        Assertions.assertEquals(List.of(block, "SELECT 3"),
                sqlOf(parser.parserSqlScript(block + ";\nSELECT 3;")));
    }

    @Test
    void beginTransactionRemainsATopLevelStatement() {
        String script = """
                BEGIN TRANSACTION;
                UPDATE `project.dataset.table` SET value = 1 WHERE id = 1;
                COMMIT TRANSACTION;
                SELECT 1;
                """;

        Assertions.assertEquals(List.of(
                "BEGIN TRANSACTION",
                "UPDATE `project.dataset.table` SET value = 1 WHERE id = 1",
                "COMMIT TRANSACTION",
                "SELECT 1"), sqlOf(parser.parserSqlScript(script)));
    }

    @Test
    void keepsCreateProcedureBodyTogether() {
        String procedure = """
                CREATE PROCEDURE `project.dataset.increment`(value INT64)
                BEGIN
                  SELECT value + 1;
                END
                """.strip();

        Assertions.assertEquals(List.of(procedure, "SELECT 2"),
                sqlOf(parser.parserSqlScript(procedure + ";\nSELECT 2;")));
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
    void keepsSetOptionsValuesTogetherAndRestoresTopLevelSplitting() {
        String withDescriptionAndLabels = """
                ALTER TABLE `project.dataset.events`
                SET OPTIONS (
                  description = 'owner''s; event table',
                  labels = [('team', 'data;platform')])
                """.strip();
        String clearDescription = """
                ALTER TABLE `project.dataset.events`
                SET OPTIONS (description = NULL)
                """.strip();
        String emptyDescription = """
                ALTER TABLE `project.dataset.events`
                SET OPTIONS (description = '')
                """.strip();
        String select = "SELECT * FROM `project.dataset.events`";

        String script = String.join(";\n",
                withDescriptionAndLabels, clearDescription, emptyDescription, select) + ";";

        Assertions.assertEquals(List.of(
                withDescriptionAndLabels,
                clearDescription,
                emptyDescription,
                select), sqlOf(parser.parserSqlScript(script)));
    }

    @Test
    void distinguishesCommentMarkersFromQuotedTextAndOperators() {
        String quoted = "SELECT '-- text', '# text', '/* text */', `project.--dataset./*table*/`";
        String arithmetic = "SELECT 8 / 2, 5 - -1";

        Assertions.assertEquals(List.of(quoted, arithmetic, "SELECT 3"),
                sqlOf(parser.parserSqlScript(quoted + "; " + arithmetic + "; SELECT 3;")));
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
