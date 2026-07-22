package ai.chat2db.plugin.postgresql.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionEditorHintTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionMetadataResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgreSqlEditorHintProviderTest {

    private final PostgreSqlEditorHintProvider provider = new PostgreSqlEditorHintProvider();

    @Test
    void buildsTypeAwareDefaultsForExplicitInsertColumns() {
        String sql = "INSERT INTO public.demo (id, enabled, name, created_at, payload) VALUES (";

        List<SqlCompletionEditorHint> hints = provider.build(request(sql));

        assertEquals(1, hints.size());
        assertEquals(SqlCompletionEditorHintTypeEnum.INSERT_VALUE, hints.get(0).getType());
        assertEquals(List.of("0", "FALSE", "''", "CURRENT_TIMESTAMP", "'{}'::jsonb"),
                hints.get(0).getItems().stream().map(SqlCompletionEditorHint.Item::getDefaultValue).toList());
        assertTrue(hints.get(0).getItems().get(0).isActive());
        assertEquals("enabled:boolean", hints.get(0).getItems().get(1).getLabel());
    }

    @Test
    void supportsQuotedPostgreSqlIdentifiers() {
        String sql = "insert into \"public\".\"demo\" (\"id\", \"name\") values (";

        List<SqlCompletionEditorHint> hints = provider.build(request(sql));

        assertEquals(1, hints.size());
        assertEquals(List.of("id", "name"),
                hints.get(0).getItems().stream().map(SqlCompletionEditorHint.Item::getFieldName).toList());
    }

    @Test
    void keepsFieldTypeLabelsAfterDefaultsAreInserted() {
        String sql = "INSERT INTO demo (id, enabled, name) VALUES (0, FALSE, ''";

        List<SqlCompletionEditorHint> hints = provider.build(request(sql));

        assertEquals(1, hints.size());
        assertEquals(3, hints.get(0).getItems().size());
        assertEquals("name:character varying", hints.get(0).getItems().get(2).getLabel());
        assertEquals(sql.length(), hints.get(0).getItems().get(2).getRange().getEndColumn() - 1);
    }

    @Test
    void doesNotHintInsertWithoutExplicitColumnList() {
        String sql = "INSERT INTO public.demo VALUES (";

        assertTrue(provider.build(request(sql)).isEmpty());
    }

    private DbSqlCompletionRequest request(String sql) {
        return DbSqlCompletionRequest.of(sql, sql.length(), "POSTGRESQL", 0, metadataRequest ->
                SqlCompletionMetadataResponse.of(List.of(
                        column("id", "integer", 1),
                        column("enabled", "boolean", 2),
                        column("name", "character varying", 3),
                        column("created_at", "timestamp without time zone", 4),
                        column("payload", "jsonb", 5))));
    }

    private SqlCompletionCandidate column(String name, String type, int rank) {
        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.COLUMN, name);
        candidate.setColumnName(name);
        candidate.setDataType(type);
        candidate.setSortRank(rank);
        return candidate;
    }
}
