package ai.chat2db.plugin.postgresql.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionEditorHintTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionMetadataRequest;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionMetadataResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PostgreSqlInsertEditorHintProviderTest {

    private final PostgreSqlInsertEditorHintProvider provider = new PostgreSqlInsertEditorHintProvider();

    @Test
    void buildsPostgreSqlDefaultsForExplicitColumns() {
        String sql = "INSERT INTO app.demo "
                + "(id, enabled, name, birthday, created_at, payload, document, tags, binary_value) VALUES (";
        List<SqlCompletionCandidate> columns = List.of(
                column("id", "integer", 1),
                column("enabled", "boolean", 2),
                column("name", "varchar(100)", 3),
                column("birthday", "date", 4),
                column("created_at", "timestamp", 5),
                column("payload", "jsonb", 6),
                column("document", "json", 7),
                column("tags", "text[]", 8),
                column("binary_value", "bytea", 9));

        List<SqlCompletionEditorHint> hints = provider.build(request(sql, null, columns));

        assertEquals(1, hints.size());
        assertEquals(SqlCompletionEditorHintTypeEnum.INSERT_VALUE, hints.get(0).getType());
        assertEquals(List.of(
                        "0", "FALSE", "''", "CURRENT_DATE", "CURRENT_TIMESTAMP",
                        "'{}'::jsonb", "'{}'::json", "'{}'", "'\\x'::bytea"),
                hints.get(0).getItems().stream().map(SqlCompletionEditorHint.Item::getDefaultValue).toList());
        assertEquals("enabled:boolean", hints.get(0).getItems().get(1).getLabel());
    }

    @Test
    void mapsQuotedSchemaAndTableToMetadataScope() {
        String sql = "INSERT INTO \"App\".\"Demo\" (\"id\") VALUES (";
        AtomicReference<DbSqlCompletionMetadataRequest> metadataRequest = new AtomicReference<>();

        List<SqlCompletionEditorHint> hints = provider.build(request(sql, metadataRequest));

        assertEquals(1, hints.size());
        assertNull(metadataRequest.get().scope().catalog());
        assertEquals("App", metadataRequest.get().scope().schema());
        assertEquals("Demo", metadataRequest.get().scope().table());
        assertEquals("id", hints.get(0).getItems().get(0).getFieldName());
    }

    @Test
    void keepsQuotedAndUnquotedColumnNamesDistinct() {
        String sql = "INSERT INTO demo (\"ID\", id) VALUES (";
        List<SqlCompletionCandidate> columns = List.of(
                column("ID", "boolean", 1),
                column("id", "integer", 2));

        List<SqlCompletionEditorHint> hints = provider.build(request(sql, null, columns));

        assertEquals(List.of("FALSE", "0"),
                hints.get(0).getItems().stream().map(SqlCompletionEditorHint.Item::getDefaultValue).toList());
    }

    @Test
    void matchesTypeTokensWithoutRegexBacktrackingOrPartialWords() {
        String sql = "INSERT INTO demo (amount, ratio, jsonish, update_time) VALUES (";
        List<SqlCompletionCandidate> columns = List.of(
                column("amount", "number ( 12, 0 )", 1),
                column("ratio", "number(12, 2)", 2),
                column("jsonish", "notjsonvalue", 3),
                column("update_time", "time without time zone", 4));

        List<SqlCompletionEditorHint> hints = provider.build(request(sql, null, columns));

        assertEquals(List.of("0", "0.0", "NULL", "CURRENT_TIME"),
                hints.get(0).getItems().stream().map(SqlCompletionEditorHint.Item::getDefaultValue).toList());
    }

    @Test
    void keepsDollarQuotedValueDelimitersInsideOneValue() {
        String sql = "INSERT INTO demo (id, note, enabled) VALUES (0, $tag$a,b);($tag$, FALSE";
        List<SqlCompletionCandidate> columns = List.of(
                column("id", "integer", 1),
                column("note", "text", 2),
                column("enabled", "boolean", 3));

        List<SqlCompletionEditorHint> hints = provider.build(request(sql, null, columns));

        assertEquals(List.of("0", "$tag$a,b);($tag$", "FALSE"),
                hints.get(0).getItems().stream().map(item -> valueAt(sql, item)).toList());
    }

    @Test
    void scopesHintsToCurrentStatementAndStopsAfterValuesRowCloses() {
        String sql = "INSERT INTO old_table (id) VALUES (1);\nINSERT INTO demo (id, name) VALUES (";
        AtomicReference<DbSqlCompletionMetadataRequest> metadataRequest = new AtomicReference<>();

        List<SqlCompletionEditorHint> hints = provider.build(request(sql, metadataRequest));

        assertEquals(1, hints.size());
        assertEquals("demo", metadataRequest.get().scope().table());
        assertEquals(2, hints.get(0).getStatementRange().getStartLineNumber());
        assertTrue(provider.build(request(sql + "0, '')", null)).isEmpty());
    }

    @Test
    void keepsHintsAfterValuesWereMaterialized() {
        String sql = "INSERT INTO demo (id, enabled, name) VALUES (0, FALSE, ''";

        List<SqlCompletionEditorHint> hints = provider.build(request(sql, null));

        assertEquals(3, hints.get(0).getItems().size());
        assertEquals(sql.length(), hints.get(0).getItems().get(2).getRange().getEndColumn() - 1);
    }

    @Test
    void ignoresUnsupportedInsertShapesAndUpdate() {
        assertTrue(provider.build(request("UPDATE demo SET enabled = ", null)).isEmpty());
        assertTrue(provider.build(request("INSERT INTO demo VALUES (", null)).isEmpty());
        assertTrue(provider.build(request("INSERT INTO catalog.app.demo (id) VALUES (", null)).isEmpty());
        assertTrue(provider.build(request("INSERT INTO `demo` (`id`) VALUES (", null)).isEmpty());
    }

    private DbSqlCompletionRequest request(
            String sql,
            AtomicReference<DbSqlCompletionMetadataRequest> metadataRequest) {
        return request(sql, metadataRequest, List.of(
                column("id", "integer", 1),
                column("enabled", "boolean", 2),
                column("name", "varchar(100)", 3)));
    }

    private DbSqlCompletionRequest request(
            String sql,
            AtomicReference<DbSqlCompletionMetadataRequest> metadataRequest,
            List<SqlCompletionCandidate> columns) {
        return DbSqlCompletionRequest.of(sql, sql.length(), "POSTGRESQL", 0, request -> {
            if (metadataRequest != null) {
                metadataRequest.set(request);
            }
            return SqlCompletionMetadataResponse.of(columns);
        });
    }

    private SqlCompletionCandidate column(String name, String type, int rank) {
        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.COLUMN, name);
        candidate.setColumnName(name);
        candidate.setDataType(type);
        candidate.setSortRank(rank);
        return candidate;
    }

    private String valueAt(String sql, SqlCompletionEditorHint.Item item) {
        return sql.substring(item.getRange().getStartColumn() - 1, item.getRange().getEndColumn() - 1);
    }
}
