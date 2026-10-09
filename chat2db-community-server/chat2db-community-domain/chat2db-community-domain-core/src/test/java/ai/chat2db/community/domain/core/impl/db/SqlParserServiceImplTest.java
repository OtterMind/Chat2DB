package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.parser.statement.insert.InsertValueMapping;
import ai.chat2db.community.domain.api.enums.parser.InsertValueMappingStatusEnum;
import ai.chat2db.community.domain.api.model.db.SimpleInsertValueMapping;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.parser.message.SyntaxErrorMessage;
import ai.chat2db.community.domain.api.model.parser.result.SqlParserResponse;
import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import ai.chat2db.spi.ISQLParser;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

class SqlParserServiceImplTest {

    @Test
    void normalizeIdentifierTokenPreservesEmbeddedQuotes() {
        DefaultSQLIdentifierProcessor processor = new DefaultSQLIdentifierProcessor();

        Assertions.assertEquals("A\"B",
                DbSqlParserServiceImpl.normalizeIdentifierToken(processor, "\"A\"\"B\""));
        Assertions.assertEquals("A\"B",
                DbSqlParserServiceImpl.normalizeIdentifierToken(processor, "A\"B"));
        Assertions.assertEquals("Order",
                DbSqlParserServiceImpl.normalizeIdentifierToken(processor, ".\"Order\""));
    }

    @Test
    void toTableMapKeepsTheFirstDuplicateTable() {
        Table first = new Table();
        first.setName("orders");
        Table duplicate = new Table();
        duplicate.setName("orders");

        Map<String, Table> tableMap = DbSqlParserServiceImpl.toTableMap(List.of(first, duplicate));

        Assertions.assertEquals(1, tableMap.size());
        Assertions.assertSame(first, tableMap.get("orders"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSimpleInsertValueMappingsReturnsRowTokenRange() throws Exception {
        InsertValueMapping mapping = new InsertValueMapping(
                token("name", 1, 24),
                token("name", 1, 24),
                token("'Tom'", 2, 9),
                token("'Tom'", 2, 9),
                0,
                1,
                InsertValueMappingStatusEnum.MATCHED);
        mapping.setRowFirstToken(token("(", 2, 4));
        mapping.setRowLastToken(token(")", 2, 20));

        Method method = DbSqlParserServiceImpl.class.getDeclaredMethod("getSimpleInsertValueMappings", List.class);
        method.setAccessible(true);
        List<SimpleInsertValueMapping> simpleMappings = (List<SimpleInsertValueMapping>) method.invoke(
                new DbSqlParserServiceImpl(), List.of(mapping));

        Assertions.assertEquals(1, simpleMappings.size());
        SimpleInsertValueMapping simpleMapping = simpleMappings.get(0);
        Assertions.assertEquals(2, simpleMapping.getRowStartRowNum());
        Assertions.assertEquals(5, simpleMapping.getRowStartColNum());
        Assertions.assertEquals(2, simpleMapping.getRowEndRowNum());
        Assertions.assertEquals(22, simpleMapping.getRowEndColNum());
        Assertions.assertEquals("MATCHED", simpleMapping.getMappingStatus());
    }

    @Test
    void syntaxErrorsCausedOnlyByParametersAreDropped() {
        List<String> parsedSql = new ArrayList<>();
        ISQLParser parser = parserReportingErrorsFor(parsedSql, ":", "?");
        String sql = "SELECT name FROM books WHERE id = :id AND slug = ?;";

        List<SyntaxErrorMessage> errors = DbSqlParserServiceImpl.syntaxErrorsIgnoringParameters(
                parser, sql, "MARIADB", List.of(syntaxError()));

        Assertions.assertEquals(List.of(), errors);
        Assertions.assertEquals(List.of("SELECT name FROM books WHERE id = 000 AND slug = 0;"), parsedSql);
    }

    @Test
    void realSyntaxErrorsNextToParametersAreKept() {
        List<String> parsedSql = new ArrayList<>();
        ISQLParser parser = parserReportingErrorsFor(parsedSql, "FORM");
        SyntaxErrorMessage error = syntaxError();

        List<SyntaxErrorMessage> errors = DbSqlParserServiceImpl.syntaxErrorsIgnoringParameters(
                parser, "SELECT name FORM books WHERE id = :id", "MYSQL", List.of(error));

        Assertions.assertEquals(1, errors.size());
        Assertions.assertEquals(1, parsedSql.size());
    }

    @Test
    void syntaxErrorsWithoutParametersAreNotReparsed() {
        List<String> parsedSql = new ArrayList<>();
        ISQLParser parser = parserReportingErrorsFor(parsedSql, "FORM");
        SyntaxErrorMessage error = syntaxError();

        List<SyntaxErrorMessage> errors = DbSqlParserServiceImpl.syntaxErrorsIgnoringParameters(
                parser, "SELECT name FORM books", "MYSQL", List.of(error));

        Assertions.assertEquals(List.of(error), errors);
        Assertions.assertEquals(List.of(), parsedSql);
    }

    /**
     * A parser stub that records each parsed SQL and reports one syntax error when it contains any marker.
     */
    private static ISQLParser parserReportingErrorsFor(List<String> parsedSql, String... errorMarkers) {
        return (ISQLParser) Proxy.newProxyInstance(ISQLParser.class.getClassLoader(), new Class<?>[]{ISQLParser.class},
                (proxy, method, args) -> {
                    if (!"parserStatements".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String sql = (String) args[0];
                    parsedSql.add(sql);
                    boolean hasError = Arrays.stream(errorMarkers).anyMatch(sql::contains);
                    return SqlParserResponse.builder()
                            .statements(List.of())
                            .syntaxErrors(hasError ? List.of(syntaxError()) : List.of())
                            .build();
                });
    }

    private static SyntaxErrorMessage syntaxError() {
        return new SyntaxErrorMessage(1, 0, 1, "x", "syntax error");
    }

    private static Token token(String text, int line, int charPositionInLine) {
        CommonToken token = new CommonToken(0, text);
        token.setLine(line);
        token.setCharPositionInLine(charPositionInLine);
        return token;
    }
}
