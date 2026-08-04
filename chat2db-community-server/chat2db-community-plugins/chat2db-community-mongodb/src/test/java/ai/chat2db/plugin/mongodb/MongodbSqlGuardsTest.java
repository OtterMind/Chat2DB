package ai.chat2db.plugin.mongodb;

import java.util.List;

import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import ai.chat2db.spi.constant.SQLConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongodbSqlGuardsTest {

    @Test
    void requireDatabaseNameAcceptsSafeUseTokens() {
        assertEquals("mydb", MongodbSqlGuards.requireDatabaseName("mydb"));
        assertEquals("my-db_1", MongodbSqlGuards.requireDatabaseName("my-db_1"));
    }

    @Test
    void requireDatabaseNameRejectsCommandBreakout() {
        assertThrows(IllegalArgumentException.class,
            () -> MongodbSqlGuards.requireDatabaseName("x; db.dropDatabase(); //"));
        assertThrows(IllegalArgumentException.class,
            () -> MongodbSqlGuards.requireDatabaseName("a b"));
        assertThrows(IllegalArgumentException.class,
            () -> MongodbSqlGuards.requireDatabaseName(""));
        assertThrows(IllegalArgumentException.class,
            () -> MongodbSqlGuards.requireDatabaseName(null));
    }

    @Test
    void collectionAndFieldNamesUseQuotedJavaScriptContexts() {
        assertEquals("getCollection(\"my-field.1\")", MongodbSqlGuards.collectionAccessor("my-field.1"));
        assertEquals("getCollection(\"1users\")", MongodbSqlGuards.collectionAccessor("1users"));
        assertEquals("\"my-field\"", MongodbSqlGuards.quoteFieldName("my-field"));
        assertEquals("\"1field\"", MongodbSqlGuards.quoteFieldName("1field"));
        assertEquals("getCollection(\"x\\\"); db.dropDatabase(); //\")",
            MongodbSqlGuards.collectionAccessor("x\"); db.dropDatabase(); //"));
        assertEquals("\"x\\\":1, $where:\"", MongodbSqlGuards.quoteFieldName("x\":1, $where:"));
        assertThrows(IllegalArgumentException.class, () -> MongodbSqlGuards.collectionAccessor(""));
        assertThrows(IllegalArgumentException.class, () -> MongodbSqlGuards.quoteFieldName(null));
    }

    @Test
    void escapeJsonStringEscapesQuotesBackslashAndControls() {
        assertEquals("plain", MongodbSqlGuards.escapeJsonString("plain"));
        assertEquals("a\\\"b", MongodbSqlGuards.escapeJsonString("a\"b"));
        assertEquals("a\\\\b", MongodbSqlGuards.escapeJsonString("a\\b"));
        assertEquals("a\\nb", MongodbSqlGuards.escapeJsonString("a\nb"));
        assertEquals("\\u0001", MongodbSqlGuards.escapeJsonString("\u0001"));
        assertEquals("\\u2028\\u2029", MongodbSqlGuards.escapeJsonString("\u2028\u2029"));
        assertEquals("\\ud83d\\ude00", MongodbSqlGuards.escapeJsonString("\ud83d\ude00"));
        assertNull(MongodbSqlGuards.escapeJsonString(null));
    }

    @Test
    void dropTableUsesAQuotedCollectionAccessor() {
        MongodbDBManager manager = new MongodbDBManager();
        assertEquals("db.getCollection(\"users\").drop()", manager.dropTable(null, null, null, "users"));
        assertEquals("db.getCollection(\"users; db.dropDatabase(); //\").drop()",
            manager.dropTable(null, null, null, "users; db.dropDatabase(); //"));
    }

    @Test
    void truncateTableSupportsNonPropertyCollectionNames() throws Exception {
        MongodbDBManager manager = new MongodbDBManager();
        assertEquals("db.getCollection(\"users\").deleteMany({})",
            manager.truncateTable(null, null, null, "users"));
        assertEquals("db.getCollection(\"a.b\").deleteMany({})",
            manager.truncateTable(null, null, null, "a.b"));
    }

    @Test
    void editableQueryParsingKeepsCollectionNameAndEditabilityAligned() {
        MongodbScriptExecutor executor = new MongodbScriptExecutor();
        assertTrue(executor.canEdit("db.users.find({})"));
        assertEquals("users", executor.getTableName(null, "db.users.find({})"));

        String collectionName = "my-field.1\"); \\ quoted";
        String generatedQuery = "db." + MongodbSqlGuards.collectionAccessor(collectionName) + ".find()";
        assertTrue(executor.canEdit(generatedQuery));
        assertEquals(collectionName, executor.getTableName(null, generatedQuery));

        assertFalse(executor.canEdit("db.getCollection(collectionName).find()"));
        assertEquals("", executor.getTableName(null, "db.getCollection(collectionName).find()"));
        assertEquals("explicit", executor.getTableName("explicit", generatedQuery));
    }

    @Test
    void selectTableCommandUsesAQuotedCollectionAccessor() {
        assertEquals("db.getCollection(\"my-field.1\").find()",
            MongodbScriptExecutor.selectTableCommand("my-field.1"));
        assertEquals("db.getCollection(\"x\\\"); db.dropDatabase(); //\").find()",
            MongodbScriptExecutor.selectTableCommand("x\"); db.dropDatabase(); //"));
    }

    @Test
    void deleteCommandEscapesObjectIdAndValidatesTable() {
        QueryResponse response = new QueryResponse();
        response.setTableName("users");
        response.setHeaderList(List.of(
            Header.builder().name("rn").build(),
            Header.builder().name("_id").build()));
        ResultOperation operation = new ResultOperation();
        operation.setType(SQLConstants.DELETE_KEYWORD);
        operation.setOldDataList(List.of("1", "abc123"));
        response.setOperations(List.of(operation));
        assertEquals("db.getCollection(\"users\").deleteOne({_id: ObjectId(\"abc123\")})",
            MongodbSqlBuilder.getInstance().buildByQueryResult(response));

        operation.setOldDataList(List.of("1", "a\"), $where: 1, x: (\""));
        String sql = MongodbSqlBuilder.getInstance().buildByQueryResult(response);
        assertEquals("db.getCollection(\"users\").deleteOne({_id: ObjectId(\"a\\\"), $where: 1, x: (\\\"\")})", sql);

        response.setTableName("users\"); db.dropDatabase(); //");
        assertEquals("db.getCollection(\"users\\\"); db.dropDatabase(); //\").deleteOne({_id: ObjectId(\"a\\\"), $where: 1, x: (\\\"\")})",
            MongodbSqlBuilder.getInstance().buildByQueryResult(response));
    }

    @Test
    void insertCommandEscapesValuesAndValidatesNames() {
        QueryResponse response = new QueryResponse();
        response.setTableName("users");
        response.setHeaderList(List.of(
            Header.builder().name("rn").build(),
            Header.builder().name("_id").build(),
            Header.builder().name("name").build()));
        ResultOperation operation = new ResultOperation();
        operation.setType(SQLConstants.CREATE_KEYWORD);
        operation.setDataList(List.of("1", "id1", "a\"}), db.dropDatabase(), //"));
        response.setOperations(List.of(operation));
        String sql = MongodbSqlBuilder.getInstance().buildByQueryResult(response);
        assertEquals("db.getCollection(\"users\").insertOne({\"name\":\"a\\\"}), db.dropDatabase(), //\"})", sql);

        response.setHeaderList(List.of(
            Header.builder().name("rn").build(),
            Header.builder().name("_id").build(),
            Header.builder().name("a}), x:(").build()));
        assertEquals("db.getCollection(\"users\").insertOne({\"a}), x:(\":\"a\\\"}), db.dropDatabase(), //\"})",
            MongodbSqlBuilder.getInstance().buildByQueryResult(response));
    }

    @Test
    void updateCommandEscapesValuesAndId() {
        QueryResponse response = new QueryResponse();
        response.setTableName("users");
        response.setHeaderList(List.of(
            Header.builder().name("rn").build(),
            Header.builder().name("_id").build(),
            Header.builder().name("name").build()));
        ResultOperation operation = new ResultOperation();
        operation.setType(SQLConstants.UPDATE_KEYWORD);
        operation.setOldDataList(List.of("1", "id\"1", "old"));
        operation.setDataList(List.of("1", "id\"1", "new\"value"));
        response.setOperations(List.of(operation));
        String sql = MongodbSqlBuilder.getInstance().buildByQueryResult(response);
        assertEquals("db.getCollection(\"users\").updateOne({_id:ObjectId(\"id\\\"1\")},{$set:{\"name\":\"new\\\"value\"}})", sql);
    }
}
