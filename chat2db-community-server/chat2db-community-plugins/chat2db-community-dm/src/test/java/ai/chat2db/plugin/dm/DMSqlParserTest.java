package ai.chat2db.plugin.dm;

import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.parser.result.SqlParserResponse;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.plugin.dm.parser.DMSqlParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DMSqlParserTest {

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("explainStatements")
    void parserStatementsShouldPreserveExplainAsOuterStatementType(String sql) {
        assertExplainType(new DMSqlParser().parserStatements(sql), sql);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("explainStatements")
    void simpleParserStatementsShouldPreserveExplainAsOuterStatementType(String sql) {
        assertExplainType(new DMSqlParser().simpleParserStatements(sql), sql);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("explainStatements")
    void validTableStatementsShouldPreserveExplainAsOuterStatementType(String sql) {
        assertExplainType(new DMSqlParser().validTableStatements(sql), sql);
    }

    private static Stream<String> explainStatements() {
        return Stream.of(
                "EXPLAIN SELECT * FROM SYSOBJECTS",
                "EXPLAIN UPDATE SYSOBJECTS SET NAME = 'UPDATED' WHERE ID = 1",
                "EXPLAIN DELETE FROM SYSOBJECTS WHERE ID = 1",
                "EXPLAIN INSERT INTO SYSOBJECTS (ID, NAME) VALUES (1, 'CREATED')",
                "EXPLAIN MERGE INTO TARGET_TABLE T USING SOURCE_TABLE S ON (T.ID = S.ID) "
                        + "WHEN MATCHED THEN UPDATE SET T.NAME = S.NAME"
        );
    }

    private void assertExplainType(SqlParserResponse response, String sql) {
        List<Statement> statements = response.getStatements();

        assertEquals(1, statements.size(), sql);
        assertEquals(SqlTypeEnum.EXPLAIN.name(), statements.get(0).getType(), sql);
    }
}
