package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import java.util.ArrayList;
import java.util.List;

public class SyncSqlBatchHandler implements ISqlBatchHandler {

    private final TaskExecutionContext context;
    private final ImportSqlExecutor sqlExecutor;
    private static final int BATCH_SIZE = 1000;
    private final List<Statement> statements = new ArrayList<>(BATCH_SIZE);

    public SyncSqlBatchHandler(TaskExecutionContext context, ImportSqlExecutor sqlExecutor) {
        this.context = context;
        this.sqlExecutor = sqlExecutor;

    }

    private void executeBatch(List<Statement> statements) {
        List<String> batchInsertSqls = new ArrayList<>();

        for (Statement stmt : statements) {
            context.checkCancelled();
            String sql = stmt.getSql().trim();

            if (sql.toUpperCase().startsWith("INSERT")) {
                batchInsertSqls.add(sql);
            } else {
                if (!batchInsertSqls.isEmpty()) {
                    sqlExecutor.executeBatch(batchInsertSqls);
                    batchInsertSqls.clear();
                }
                sqlExecutor.executeSql(sql);
            }
        }
        if (!batchInsertSqls.isEmpty()) {
            context.checkCancelled();
            sqlExecutor.executeBatch(batchInsertSqls);
        }
    }

    @Override
    public void handle(Statement statement) {
        context.checkCancelled();
        statements.add(statement);
        if (statements.size() >= BATCH_SIZE) {
            executeBatch(statements);
            statements.clear();
        }

    }

    @Override
    public void flush() {
        context.checkCancelled();
        executeBatch(statements);
        statements.clear();
    }
}
