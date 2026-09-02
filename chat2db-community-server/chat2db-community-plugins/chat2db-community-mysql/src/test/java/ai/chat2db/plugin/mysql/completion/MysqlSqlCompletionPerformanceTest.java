package ai.chat2db.plugin.mysql.completion;

import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionMetadataRequest;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionMetadataResponse;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionResponse;
import ai.chat2db.community.domain.api.service.db.ISqlCompletionMetadataProvider;
import ai.chat2db.spi.parser.completion.SqlCompletionDialectComponents;
import ai.chat2db.spi.parser.completion.SqlCompletionPipelineState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class MysqlSqlCompletionPerformanceTest {

    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 2;
    private static final long MAX_SINGLE_COMPLETION_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final SqlCompletionDialectComponents components = new MysqlSqlCompletionDialect().components();

    @Test
    void largeSqlCompletionStaysWithinPerformanceBudget() {
        List<PerformanceCase> cases = List.of(
                new PerformanceCase("single_statement_200_lines_166_ids", singleStatement(200, 166), false),
                new PerformanceCase("multi_statement_200_history_166_ids", multiStatement(200, 166), true),
                new PerformanceCase("multi_statement_200_history_500_ids", multiStatement(200, 500), true),
                new PerformanceCase("multi_statement_200_history_1000_ids", multiStatement(200, 1000), true));

        for (PerformanceCase performanceCase : cases) {
            CountingMetadataProvider metadataProvider = new CountingMetadataProvider();
            DbSqlCompletionRequest request = DbSqlCompletionRequest.of(
                    performanceCase.sql(), performanceCase.sql().length(), "MYSQL", 0, metadataProvider);
            for (int index = 0; index < WARMUP_RUNS; index++) {
                measure(request);
            }
            metadataProvider.reset();

            PipelineMeasurement lastMeasurement = null;
            for (int index = 0; index < MEASURED_RUNS; index++) {
                lastMeasurement = measure(request);
                Assertions.assertTrue(lastMeasurement.totalNanos() < MAX_SINGLE_COMPLETION_NANOS,
                        performanceCase.name());
            }

            Assertions.assertNotNull(lastMeasurement);
            Assertions.assertTrue(lastMeasurement.totalNanos() > 0, performanceCase.name());
            Assertions.assertFalse(lastMeasurement.stageNanos().isEmpty(), performanceCase.name());
            SqlCompletionResponse result = lastMeasurement.state().result();
            Assertions.assertEquals("SUCCESS", result.getStatus(), performanceCase.name());
            Assertions.assertFalse(result.getCandidates().isEmpty(), performanceCase.name());
            Assertions.assertTrue(lastMeasurement.state().window().sourceSql().length() <= performanceCase.sql().length(),
                    performanceCase.name());
            if (performanceCase.hasHistory()) {
                Assertions.assertTrue(lastMeasurement.state().window().sourceStartOffset() > 0,
                        performanceCase.name());
            }
            Assertions.assertTrue(metadataProvider.totalCalls() > 0, performanceCase.name());
        }
    }

    private PipelineMeasurement measure(DbSqlCompletionRequest request) {
        Map<String, Long> stageNanos = new LinkedHashMap<>();
        long totalStartedAtNanos = System.nanoTime();
        SqlCompletionPipelineState state = SqlCompletionPipelineState.start(request);

        long stageStartedAtNanos = System.nanoTime();
        state = state.withInput(components.inputCleaner().clean(state));
        stageNanos.put("input", elapsed(stageStartedAtNanos));

        stageStartedAtNanos = System.nanoTime();
        state = state.withWindow(components.statementLocator().locate(state));
        stageNanos.put("statementWindow", elapsed(stageStartedAtNanos));

        stageStartedAtNanos = System.nanoTime();
        state = state.withCursorContext(components.cursorAnalyzer().analyze(state));
        stageNanos.put("cursor", elapsed(stageStartedAtNanos));
        if (state.cursorContext() == null) {
            return new PipelineMeasurement(state.withResult(SqlCompletionResponse.empty()), stageNanos,
                    elapsed(totalStartedAtNanos));
        }

        stageStartedAtNanos = System.nanoTime();
        state = state.withDummySql(components.dummyBuilder().build(state));
        stageNanos.put("dummySql", elapsed(stageStartedAtNanos));

        stageStartedAtNanos = System.nanoTime();
        state = state.withLocalContext(components.localContextCollector().collect(state));
        stageNanos.put("localContext", elapsed(stageStartedAtNanos));

        stageStartedAtNanos = System.nanoTime();
        state = state.withC3Result(components.c3Collector().collect(state));
        stageNanos.put("c3", elapsed(stageStartedAtNanos));

        stageStartedAtNanos = System.nanoTime();
        state = state.withRuleEvidence(components.ruleEvidenceCollector().collect(state));
        stageNanos.put("ruleEvidence", elapsed(stageStartedAtNanos));

        stageStartedAtNanos = System.nanoTime();
        state = state.withSlots(components.slotClassifier().classify(state));
        stageNanos.put("slots", elapsed(stageStartedAtNanos));

        stageStartedAtNanos = System.nanoTime();
        state = state.withIntents(components.intentResolver().resolve(state));
        stageNanos.put("intents", elapsed(stageStartedAtNanos));

        stageStartedAtNanos = System.nanoTime();
        state = state.withCandidatePlan(components.candidatePlanner().plan(state));
        stageNanos.put("candidatePlan", elapsed(stageStartedAtNanos));

        stageStartedAtNanos = System.nanoTime();
        state = state.withResult(components.presentationProcessor().process(state));
        stageNanos.put("presentation", elapsed(stageStartedAtNanos));
        return new PipelineMeasurement(state, stageNanos, elapsed(totalStartedAtNanos));
    }

    private static long elapsed(long startedAtNanos) {
        return Math.max(0L, System.nanoTime() - startedAtNanos);
    }

    private static String singleStatement(int targetLineCount, int idCount) {
        StringBuilder sql = currentStatementPrefix(idCount);
        int lineCount = 2 + idCount / 10;
        while (lineCount < targetLineCount - 1) {
            sql.append("-- filler\n");
            lineCount++;
        }
        return sql.append("and na").toString();
    }

    private static String multiStatement(int historyStatementCount, int idCount) {
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < historyStatementCount; index++) {
            sql.append("select * from archive_").append(index).append(";\n");
        }
        return sql.append(currentStatementPrefix(idCount)).append("and na").toString();
    }

    private static StringBuilder currentStatementPrefix(int idCount) {
        StringBuilder sql = new StringBuilder("select * from orders where id in (\n");
        for (int index = 0; index < idCount; index++) {
            if (index > 0) {
                sql.append(',');
            }
            sql.append(index);
            if (index % 10 == 9) {
                sql.append('\n');
            }
        }
        return sql.append(")\n");
    }

    private record PerformanceCase(String name, String sql, boolean hasHistory) {
    }

    private record PipelineMeasurement(SqlCompletionPipelineState state,
                                       Map<String, Long> stageNanos,
                                       long totalNanos) {
    }

    private static final class CountingMetadataProvider implements ISqlCompletionMetadataProvider {

        private final Map<String, Integer> counts = new LinkedHashMap<>();

        @Override
        public SqlCompletionMetadataResponse list(DbSqlCompletionMetadataRequest request) {
            counts.merge(request.type(), 1, Integer::sum);
            SqlCompletionCandidateTypeEnum type = SqlCompletionCandidateTypeEnum.from(request.type());
            if (type == SqlCompletionCandidateTypeEnum.COLUMN) {
                return SqlCompletionMetadataResponse.of(List.of(column("name"), column("status")));
            }
            if (type == SqlCompletionCandidateTypeEnum.DATABASE) {
                return SqlCompletionMetadataResponse.of(List.of(
                        SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.DATABASE, "app")));
            }
            return SqlCompletionMetadataResponse.of(new ArrayList<>());
        }

        private static SqlCompletionCandidate column(String name) {
            SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.COLUMN, name);
            candidate.setColumnName(name);
            candidate.setTableName("orders");
            return candidate;
        }

        private int totalCalls() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        private void reset() {
            counts.clear();
        }
    }
}
