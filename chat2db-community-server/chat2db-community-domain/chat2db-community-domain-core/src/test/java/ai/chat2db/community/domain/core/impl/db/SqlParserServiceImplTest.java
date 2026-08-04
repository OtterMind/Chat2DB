package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.parser.statement.insert.InsertValueMapping;
import ai.chat2db.community.domain.api.enums.parser.InsertValueMappingStatusEnum;
import ai.chat2db.community.domain.api.model.db.SimpleInsertValueMapping;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
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

    private static Token token(String text, int line, int charPositionInLine) {
        CommonToken token = new CommonToken(0, text);
        token.setLine(line);
        token.setCharPositionInLine(charPositionInLine);
        return token;
    }
}
