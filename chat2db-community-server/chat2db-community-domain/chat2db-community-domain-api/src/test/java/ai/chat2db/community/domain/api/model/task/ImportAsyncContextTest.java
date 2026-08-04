package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.api.service.task.ITaskImportSqlExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportAsyncContextTest {

    @Test
    void stoppedContextDoesNotInvokeSqlExecutor() {
        AtomicInteger executions = new AtomicInteger();
        ImportAsyncContext context = new ImportAsyncContext(null, null, "SQL", "users", null);
        context.setSqlExecutor(new ITaskImportSqlExecutor() {
            @Override
            public String executeBatch(int batch, List<String> sqls) {
                executions.incrementAndGet();
                return "success";
            }

            @Override
            public String executeSql(int batch, String sql) {
                executions.incrementAndGet();
                return "success";
            }
        });
        context.stop();

        assertThrows(CancellationException.class,
                () -> context.execute(List.of("insert into users values (1)")));
        assertThrows(CancellationException.class,
                () -> context.execute("delete from users"));
        assertEquals(0, executions.get());
    }

    @Test
    void executePassesContextCancellationHooksToSqlExecutor() {
        AtomicReference<ISqlExecutionStatementListener> batchListener = new AtomicReference<>();
        AtomicReference<Runnable> batchChecker = new AtomicReference<>();
        AtomicReference<ISqlExecutionStatementListener> sqlListener = new AtomicReference<>();
        AtomicReference<Runnable> sqlChecker = new AtomicReference<>();
        ImportAsyncContext context = new ImportAsyncContext(null, null, "SQL", "users", null);
        context.setSqlExecutor(new ITaskImportSqlExecutor() {
            @Override
            public String executeBatch(int batch, List<String> sqls) {
                return "success";
            }

            @Override
            public String executeBatch(int batch, List<String> sqls,
                                       ISqlExecutionStatementListener statementListener,
                                       Runnable cancellationChecker) {
                batchListener.set(statementListener);
                batchChecker.set(cancellationChecker);
                return "success";
            }

            @Override
            public String executeSql(int batch, String sql) {
                return "success";
            }

            @Override
            public String executeSql(int batch, String sql,
                                     ISqlExecutionStatementListener statementListener,
                                     Runnable cancellationChecker) {
                sqlListener.set(statementListener);
                sqlChecker.set(cancellationChecker);
                return "success";
            }
        });

        context.execute(List.of("insert into users values (1)"));
        context.execute("delete from users");

        assertSame(context, batchListener.get());
        assertSame(context, sqlListener.get());
        assertDoesNotThrow(batchChecker.get()::run);
        assertDoesNotThrow(sqlChecker.get()::run);
    }
}
