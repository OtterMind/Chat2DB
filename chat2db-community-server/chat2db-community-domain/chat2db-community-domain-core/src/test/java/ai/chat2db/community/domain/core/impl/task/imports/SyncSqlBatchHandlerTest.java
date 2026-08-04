package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.task.ImportAsyncContext;
import ai.chat2db.community.domain.api.service.task.ITaskImportSqlExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncSqlBatchHandlerTest {

    @Test
    void flushDoesNotExecuteStatementsAfterStop() {
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
        SyncSqlBatchHandler handler = new SyncSqlBatchHandler(context);
        handler.handle(new Statement("delete from users"));
        context.stop();

        assertThrows(CancellationException.class, handler::flush);
        assertEquals(0, executions.get());
    }
}
