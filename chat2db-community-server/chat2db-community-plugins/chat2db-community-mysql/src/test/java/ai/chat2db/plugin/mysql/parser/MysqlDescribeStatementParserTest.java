package ai.chat2db.plugin.mysql.parser;

import ai.chat2db.community.domain.api.enums.parser.IdentifierTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.parser.result.SqlParserResponse;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.token.Identifier;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlDescribeStatementParserTest {

    @Test
    void parsesDescribeTargetsWithSourceRanges() {
        assertDescribeTable("DESC user_info;", null, "user_info", "user_info");
        assertDescribeTable("DESCRIBE user_info;", null, "user_info", "user_info");
        assertDescribeTable("DESC user_info column_name;", null, "user_info", "user_info");
        assertDescribeTable("DESC database_name.user_info;", "database_name", "user_info", ".user_info");
        assertDescribeTable("DESCRIBE `database_name`.`user_info`;", "database_name", "user_info", "`user_info`");
    }

    @Test
    void doesNotCreateTableIdentifiersForIncompleteDescribeStatements() {
        assertNoTableIdentifier("DESC");
        assertNoTableIdentifier("DESCRIBE;");
    }

    @Test
    void preservesExplainTableBehavior() {
        Statement statement = parseSingleStatement("EXPLAIN database_name.user_info;");

        assertNull(statement.getType());
        assertTrue(statement.getIdentifiers().isEmpty());
    }

    private void assertDescribeTable(String sql, String databaseName, String tableName, String sourceToken) {
        Statement statement = parseSingleStatement(sql);
        assertEquals(SqlTypeEnum.DESCRIBE.name(), statement.getType());

        List<Identifier> tableIdentifiers = statement.getIdentifiers().stream()
                .filter(identifier -> IdentifierTypeEnum.TABLE.name().equals(identifier.getIdentifierType()))
                .toList();
        assertEquals(1, tableIdentifiers.size());

        Identifier tableIdentifier = tableIdentifiers.get(0);
        assertEquals(tableName, tableIdentifier.getIdentifierName());
        assertEquals(databaseName, tableIdentifier.getIdentifierDatabase());

        Token token = tableIdentifier.getFirstToken();
        assertEquals(sourceToken, token.getText());
        assertEquals(sql.indexOf(sourceToken), token.getStartIndex());
        assertEquals(sourceToken, sql.substring(token.getStartIndex(), token.getStopIndex() + 1));

        if (databaseName == null) {
            assertNull(tableIdentifier.getIdentifierDatabase());
        } else {
            assertTrue(statement.getIdentifiers().stream().anyMatch(identifier ->
                    IdentifierTypeEnum.DATABASE.name().equals(identifier.getIdentifierType())
                            && databaseName.equals(identifier.getIdentifierName())));
        }
    }

    private void assertNoTableIdentifier(String sql) {
        SqlParserResponse response = new MysqlSqlParser().parserStatements(sql);
        assertFalse(response.getStatements().stream().flatMap(statement -> statement.getIdentifiers().stream())
                .anyMatch(identifier -> IdentifierTypeEnum.TABLE.name().equals(identifier.getIdentifierType())));
    }

    private Statement parseSingleStatement(String sql) {
        SqlParserResponse response = new MysqlSqlParser().parserStatements(sql);
        assertEquals(1, response.getStatements().size());
        return response.getStatements().get(0);
    }
}
