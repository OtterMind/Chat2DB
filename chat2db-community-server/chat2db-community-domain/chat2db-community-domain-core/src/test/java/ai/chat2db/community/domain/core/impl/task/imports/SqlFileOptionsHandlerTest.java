package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlFileOptionsHandlerTest {

    @Test
    void commitsConfiguredBatchesAndRestoresAutoCommit() {
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        Connection connection = connection(commits, rollbacks);
        SqlFileOptionsHandler handler = new SqlFileOptionsHandler(spec("BATCH", "STOP", 2), context(), connection);

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));
        handler.handle(new Statement("INSERT INTO test VALUES (2)"));
        handler.handle(new Statement("INSERT INTO test VALUES (3)"));
        handler.flush();

        assertEquals(2, commits.get());
        assertEquals(0, rollbacks.get());
    }

    @Test
    void rejectsTransactionControlInTransactionModes() {
        Connection connection = connection(new AtomicInteger(), new AtomicInteger());
        SqlFileOptionsHandler handler = new SqlFileOptionsHandler(spec("SINGLE_TRANSACTION", "STOP", 1),
                context(), connection);

        assertThrows(TaskExecutionException.class, () -> handler.handle(new Statement("COMMIT")));
    }

    private ImportTaskSpec spec(String commitMode, String errorPolicy, int batchSize) {
        return ImportTaskSpec.builder().commitMode(commitMode).errorPolicy(errorPolicy).batchSize(batchSize).build();
    }

    private Connection connection(AtomicInteger commits, AtomicInteger rollbacks) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "setAutoCommit", "close" -> null;
                    case "commit" -> {
                        commits.incrementAndGet();
                        yield null;
                    }
                    case "rollback" -> {
                        rollbacks.incrementAndGet();
                        yield null;
                    }
                    case "createStatement" -> Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class[]{java.sql.Statement.class}, (statementProxy, statementMethod, statementArgs) ->
                                    statementMethod.getName().equals("execute") ? false : null);
                    case "isClosed" -> false;
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> null;
                });
    }

    private TaskExecutionContext context() {
        return new TaskExecutionContext() {
            @Override public void reportProgress(int progress, String stage, String message) { }
            @Override public void logInfo(String code, String message) { }
            @Override public void logInfo(String code, String message, Map<String, Object> details) { }
            @Override public void logWarn(String code, String message, Map<String, Object> details) { }
            @Override public void logError(String code, String message, Map<String, Object> details) { }
            @Override public void checkCancelled() { }
            @Override public void registerCancelable(TaskCancelable resource) { }
            @Override public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) { return null; }
            @Override public void write(String content) { }
            @Override public void onStatementCreated(java.sql.Statement statement) { }
            @Override public void onStatementClosed(java.sql.Statement statement) { }
        };
    }
}
