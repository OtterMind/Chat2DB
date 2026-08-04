package ai.chat2db.plugin.gaussdb;

import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionEditorHintTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionMetadataResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GaussDBSyntaxPluginTest {

    @Test
    void providesInsertEditorHintsLikePostgreSqlSibling() {
        String sql = "INSERT INTO app.demo (id, enabled, created_at) VALUES (";
        List<SqlCompletionCandidate> columns = List.of(
                column("id", "integer", 1),
                column("enabled", "boolean", 2),
                column("created_at", "timestamp", 3));
        DbSqlCompletionRequest request = DbSqlCompletionRequest.of(
                sql, sql.length(), "GAUSSDB", 0,
                metadataRequest -> SqlCompletionMetadataResponse.of(columns));

        List<SqlCompletionEditorHint> hints = new GaussDBSyntaxPlugin().getSqlEditorHints(request);

        assertEquals(1, hints.size());
        assertEquals(SqlCompletionEditorHintTypeEnum.INSERT_VALUE, hints.get(0).getType());
        assertEquals(List.of("0", "FALSE", "CURRENT_TIMESTAMP"),
                hints.get(0).getItems().stream()
                        .map(SqlCompletionEditorHint.Item::getDefaultValue).toList());
    }

    private SqlCompletionCandidate column(String name, String type, int rank) {
        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.COLUMN, name);
        candidate.setColumnName(name);
        candidate.setDataType(type);
        candidate.setSortRank(rank);
        return candidate;
    }
}
