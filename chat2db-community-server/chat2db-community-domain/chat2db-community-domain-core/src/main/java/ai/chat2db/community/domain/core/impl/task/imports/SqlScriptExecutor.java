package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** SQL-script reporting is deliberately independent of data-file import event throttling. */
public final class SqlScriptExecutor implements DefaultSQLExecutor.JdbcBatchObserver {
    private final TaskExecutionContext context;
    private final long startedAt = System.nanoTime();
    private int batches;
    private long statements;
    private long affectedRows;
    private long unknownStatements;

    public SqlScriptExecutor(TaskExecutionContext context) {
        this.context = context;
    }

    public void executeBatch(List<String> sqls) {
        List<String> inserts = new ArrayList<>();
        for (String sql : sqls) {
            context.checkCancelled();
            if (sql == null || sql.isBlank()) continue;
            if (sql.trim().toUpperCase(Locale.ROOT).startsWith("INSERT")) inserts.add(sql);
            else {
                flush(inserts);
                executeSql(sql);
            }
        }
        flush(inserts);
    }

    private void flush(List<String> inserts) {
        if (inserts.isEmpty()) return;
        try {
            DefaultSQLExecutor.getInstance().executeJdbcBatchInsertWithReport(Chat2DBContext.getConnection(),
                    List.copyOf(inserts), context, context::checkCancelled, 500, this);
            inserts.clear();
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw ImportSqlExecutor.importFailure(e);
        }
    }

    public void executeSql(String sql) {
        if (sql == null || sql.isBlank()) return;
        context.checkCancelled();
        List<String> batch = List.of(sql);
        started(batch);
        long started = System.nanoTime();
        Integer count;
        try {
            var response = DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), sql,
                    context, context::checkCancelled);
            // A result set has no affected rows. Missing DML counts remain unknown.
            count = response.getUpdateCount();
            if (count == null && response.getHeaderList() != null) count = 0;
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            completed(batch, new int[0], false, (System.nanoTime() - started) / 1_000_000);
            throw ImportSqlExecutor.importFailure(e);
        }
        completed(batch, new int[]{count == null ? Statement.SUCCESS_NO_INFO : Math.max(-2, count)},
                true, (System.nanoTime() - started) / 1_000_000);
        context.checkCancelled();
    }

    @Override
    public void started(List<String> sqls) {
        context.checkCancelled();
        batches++;
    }

    @Override
    public void completed(List<String> sqls, int[] counts, boolean successful, long elapsedMillis) {
        long knownRows = 0;
        int unknown = 0;
        int confirmed = 0;
        int failed = 0;
        for (int i = 0; i < sqls.size(); i++) {
            if (i >= counts.length) { unknown++; continue; }
            if (counts[i] == Statement.EXECUTE_FAILED) { failed++; continue; }
            confirmed++;
            if (counts[i] >= 0) knownRows += counts[i];
            else unknown++;
        }
        statements += confirmed;
        affectedRows += knownRows;
        unknownStatements += unknown;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sqlScript", true);
        details.put("batch", batches);
        details.put("statementCount", sqls.size());
        details.put("statements", List.copyOf(sqls));
        details.put("completedStatements", confirmed);
        details.put("failedStatements", failed);
        details.put("affectedRows", knownRows);
        details.put("unknownStatements", unknown);
        details.put("updateCounts", counts.clone());
        details.put("elapsedMillis", elapsedMillis);
        if (successful) context.logInfo("BATCH_EXECUTED", "SQL batch executed", details);
        else context.logError("IMPORT_BATCH_FAILED", "SQL batch failed", details);
    }

    public void reportSummary() {
        context.logInfo("IMPORT_SUMMARY", "SQL import finished", Map.of("sqlScript", true,
                "batchCount", batches, "statementCount", statements, "affectedRows", affectedRows,
                "unknownStatements", unknownStatements, "elapsedMillis", (System.nanoTime() - startedAt) / 1_000_000));
    }
}
