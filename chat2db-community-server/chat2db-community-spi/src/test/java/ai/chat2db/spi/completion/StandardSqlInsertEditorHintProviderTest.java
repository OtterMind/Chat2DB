package ai.chat2db.spi.completion;

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
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.spi.ISqlSyntaxPlugin;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StandardSqlInsertEditorHintProviderTest {

    @Test
    void buildsConservativeDefaultsForStandardInsert() {
        String sql = "INSERT INTO app.demo (id, enabled, name, birthday, created_at, payload) VALUES (";

        List<SqlCompletionEditorHint> hints = new StandardSqlInsertEditorHintProvider("H2").build(request(sql, null));

        assertEquals(1, hints.size());
        assertEquals(SqlCompletionEditorHintTypeEnum.INSERT_VALUE, hints.get(0).getType());
        assertEquals(List.of("0", "FALSE", "''", "CURRENT_DATE", "CURRENT_TIMESTAMP", "NULL"),
                hints.get(0).getItems().stream().map(SqlCompletionEditorHint.Item::getDefaultValue).toList());
        assertEquals("enabled:boolean", hints.get(0).getItems().get(1).getLabel());
    }

    @Test
    void supportsSqlServerBracketIdentifiersAndBitDefaults() {
        String sql = "INSERT INTO [dbo].[demo] ([id], [enabled], [name], [birthday], [created_at]) VALUES (";

        List<SqlCompletionEditorHint> hints = new StandardSqlInsertEditorHintProvider("SQLSERVER")
                .build(request(sql, null));

        assertEquals(List.of("0", "0", "''", "CAST(CURRENT_TIMESTAMP AS DATE)", "CURRENT_TIMESTAMP"),
                hints.get(0).getItems().stream().map(SqlCompletionEditorHint.Item::getDefaultValue).toList());
        assertEquals(List.of("id", "enabled", "name", "birthday", "created_at"),
                hints.get(0).getItems().stream().map(SqlCompletionEditorHint.Item::getFieldName).toList());
    }

    @Test
    void mapsMysqlFamilyQualifierToCatalog() {
        String sql = "INSERT INTO `sample_db`.`demo` (`id`) VALUES (";
        AtomicReference<DbSqlCompletionMetadataRequest> metadataRequest = new AtomicReference<>();

        List<SqlCompletionEditorHint> hints = new StandardSqlInsertEditorHintProvider("MARIADB")
                .build(request(sql, metadataRequest));

        assertEquals(1, hints.size());
        assertEquals("sample_db", metadataRequest.get().scope().catalog());
        assertNull(metadataRequest.get().scope().schema());
        assertEquals("demo", metadataRequest.get().scope().table());
    }

    @Test
    void keepsHintsAfterValuesWereMaterialized() {
        String sql = "INSERT INTO demo (id, enabled, name) VALUES (0, FALSE, ''";

        List<SqlCompletionEditorHint> hints = new StandardSqlInsertEditorHintProvider("H2").build(request(sql, null));

        assertEquals(3, hints.get(0).getItems().size());
        assertEquals(sql.length(), hints.get(0).getItems().get(2).getRange().getEndColumn() - 1);
    }

    @Test
    void ignoresUpdateAndInsertWithoutExplicitColumns() {
        String update = "UPDATE demo SET enabled = ";
        String insertWithoutColumns = "INSERT INTO demo VALUES (";
        StandardSqlInsertEditorHintProvider provider = new StandardSqlInsertEditorHintProvider("H2");

        assertTrue(provider.build(request(update, null)).isEmpty());
        assertTrue(provider.build(request(insertWithoutColumns, null)).isEmpty());
    }

    @Test
    void scopesHintsToTheCurrentStatementAndStopsAfterTheValuesRowCloses() {
        String sql = "INSERT INTO old_table (id) VALUES (1);\nINSERT INTO demo (id, name) VALUES (";
        AtomicReference<DbSqlCompletionMetadataRequest> metadataRequest = new AtomicReference<>();

        List<SqlCompletionEditorHint> hints = new StandardSqlInsertEditorHintProvider("H2")
                .build(request(sql, metadataRequest));

        assertEquals(1, hints.size());
        assertEquals("demo", metadataRequest.get().scope().table());
        assertEquals(2, hints.get(0).getStatementRange().getStartLineNumber());
        assertTrue(new StandardSqlInsertEditorHintProvider("H2")
                .build(request(sql + "0, '')", null)).isEmpty());
    }

    @Test
    void keepsMysqlFamilyBackslashEscapesInsideOneValue() {
        String sql = "INSERT INTO demo (name, id) VALUES ('it\\'s', 0";

        List<SqlCompletionEditorHint> hints = new StandardSqlInsertEditorHintProvider("MARIADB")
                .build(request(sql, null));

        assertEquals(2, hints.get(0).getItems().size());
        assertEquals("'it\\'s'", sql.substring(
                hints.get(0).getItems().get(0).getRange().getStartColumn() - 1,
                hints.get(0).getItems().get(0).getRange().getEndColumn() - 1));
    }

    @Test
    void usesDialectSpecificPostgresInformixAndTdengineDefaults() {
        List<SqlCompletionCandidate> columns = List.of(
                column("payload", "jsonb", 1),
                column("tags", "text[]", 2),
                column("binary_value", "bytea", 3),
                column("created_at", "timestamp", 4));
        String sql = "INSERT INTO demo (payload, tags, binary_value, created_at) VALUES (";

        assertEquals(List.of("'{}'::jsonb", "'{}'", "'\\\\x'::bytea", "CURRENT_TIMESTAMP"),
                new StandardSqlInsertEditorHintProvider("POSTGRESQL")
                        .build(request(sql, null, columns)).get(0).getItems().stream()
                        .map(SqlCompletionEditorHint.Item::getDefaultValue).toList());
        assertEquals("CURRENT", new StandardSqlInsertEditorHintProvider("INFOMIX")
                .build(request("INSERT INTO demo (created_at) VALUES (", null, columns))
                .get(0).getItems().get(0).getDefaultValue());
        assertEquals("NOW", new StandardSqlInsertEditorHintProvider("TDENGINE")
                .build(request("INSERT INTO demo (created_at) VALUES (", null, columns))
                .get(0).getItems().get(0).getDefaultValue());
    }

    @Test
    void syntaxPluginsMustExplicitlyOptInToStandardEditorHints() {
        ISqlSyntaxPlugin unsupported = new ISqlSyntaxPlugin() {
            @Override
            public String getDatabaseType() {
                return "UNSUPPORTED";
            }

            @Override
            public ISQLParser getSQLParser() {
                return null;
            }
        };

        assertTrue(unsupported.getSqlEditorHints(request("INSERT INTO demo (id) VALUES (", null)).isEmpty());
    }

    private DbSqlCompletionRequest request(String sql,
                                           AtomicReference<DbSqlCompletionMetadataRequest> metadataRequest) {
        return request(sql, metadataRequest, List.of(
                column("id", "integer", 1),
                column("enabled", "boolean", 2),
                column("name", "varchar(100)", 3),
                column("birthday", "date", 4),
                column("created_at", "timestamp", 5),
                column("payload", "blob", 6)));
    }

    private DbSqlCompletionRequest request(String sql,
                                           AtomicReference<DbSqlCompletionMetadataRequest> metadataRequest,
                                           List<SqlCompletionCandidate> columns) {
        return DbSqlCompletionRequest.of(sql, sql.length(), "H2", 0, request -> {
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
}
