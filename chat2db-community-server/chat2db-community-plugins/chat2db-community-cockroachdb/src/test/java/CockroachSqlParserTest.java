import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.plugin.cockroachdb.CockRoachSyntaxPlugin;
import ai.chat2db.plugin.cockroachdb.parser.CockroachSqlParser;
import ai.chat2db.plugin.postgresql.parser.PgsqlSqlParser;
import ai.chat2db.plugin.postgresql.parser.base.PostgreSQLLexer;
import ai.chat2db.plugin.postgresql.parser.base.PostgreSQLParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

class CockroachSqlParserTest {

    @Test
    void parserUsesPostgresqlDialectLikeRedShift() {
        Assertions.assertTrue(PgsqlSqlParser.class.isAssignableFrom(CockroachSqlParser.class),
                "CockroachDB is PostgreSQL-wire-compatible; its parser must extend PgsqlSqlParser");
    }

    @Test
    void parsesPostgresqlSyntaxThatMysqlGrammarRejected() {
        assertParsesWithoutErrors("""
                SELECT now() - INTERVAL '7 days';
                SELECT amount::numeric(10, 2) FROM orders WHERE name ILIKE 'acme%';
                SELECT id, "order total" FROM "app"."orders";
                INSERT INTO orders (id, amount) VALUES (1, 10)
                ON CONFLICT (id) DO UPDATE SET amount = excluded.amount;
                """);
    }

    @Test
    void scriptSplitterKeepsDollarQuotedFunctionBodyIntact() {
        String sql = """
                CREATE FUNCTION total_orders() RETURNS int LANGUAGE sql AS $$
                BEGIN
                    RETURN (SELECT count(*) FROM orders);
                END;
                $$;
                SELECT 1;
                """;
        List<Statement> statements = new CockroachSqlParser().parserSqlScript(sql);
        Assertions.assertEquals(2, statements.size(),
                "semicolons inside a $$-quoted body must not split the script");
        Assertions.assertEquals("$$", statements.get(0).getLastToken().getText());
    }

    @Test
    void dbTypeIdentityIsConsistentAcrossEnumPluginAndJson() throws Exception {
        Assertions.assertEquals(DatabaseTypeEnum.COCKROACHDB.name(),
                new CockRoachSyntaxPlugin().getDatabaseType());
        Assertions.assertEquals(DatabaseTypeEnum.COCKROACHDB,
                DatabaseTypeEnum.from("COCKROACHDB"));
        try (InputStream in = CockroachSqlParserTest.class
                .getResourceAsStream("/ai/chat2db/plugin/cockroachdb/cockroachdb.json")) {
            Assertions.assertNotNull(in, "cockroachdb.json must be on the classpath");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Assertions.assertTrue(json.contains("\"dbType\": \"COCKROACHDB\""),
                    "json dbType, DatabaseTypeEnum and syntax plugin must agree");
        }
    }

    private static void assertParsesWithoutErrors(String sql) {
        PostgreSQLLexer lexer = new PostgreSQLLexer(CharStreams.fromString(sql));
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PostgreSQLParser parser = new PostgreSQLParser(tokenStream);
        CountingErrorListener listener = new CountingErrorListener();
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.addErrorListener(listener);

        parser.root();

        Assertions.assertEquals(0, listener.errorCount);
    }

    private static class CountingErrorListener extends BaseErrorListener {
        private int errorCount;

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            errorCount++;
        }
    }
}
