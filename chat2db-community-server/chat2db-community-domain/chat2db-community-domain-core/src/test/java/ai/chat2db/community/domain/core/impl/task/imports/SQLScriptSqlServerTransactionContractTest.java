package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.core.impl.task.imports.sql.SQLImporter;
import ai.chat2db.plugin.sqlserver.SqlServerPlugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Production SQL Server GO parser and metadata on a driver-shaped H2 transaction fixture.
 * This is contract coverage, not a real SQL Server engine acceptance claim. */
class SQLScriptSqlServerTransactionContractTest {
    @TempDir Path directory;

    @Test void namedSavepointRollbackKeepsTheOuterTransaction() throws Exception {
        runScript("BEGIN TRANSACTION\nGO\nINSERT INTO TARGET_ROWS VALUES (1)\nGO\n"
                + "SAVE TRANSACTION saved\nGO\nINSERT INTO TARGET_ROWS VALUES (2)\nGO\n"
                + "ROLLBACK TRANSACTION saved\nGO\nINSERT INTO TARGET_ROWS VALUES (3)\nGO\nROLLBACK TRANSACTION\nGO\n");
    }

    @Test void nestedCommitDoesNotEndTheOuterTransaction() throws Exception {
        runScript("BEGIN TRANSACTION\nGO\nBEGIN TRANSACTION\nGO\nINSERT INTO TARGET_ROWS VALUES (1)\nGO\n"
                + "COMMIT TRANSACTION\nGO\nINSERT INTO TARGET_ROWS VALUES (2)\nGO\nROLLBACK TRANSACTION\nGO\n");
    }

    @Test void tranAliasAndNamedOuterRollbackKeepTheNextTransactionSafe() throws Exception {
        runScript("BEGIN TRAN outer_tx\nGO\nINSERT INTO TARGET_ROWS VALUES (1)\nGO\nROLLBACK TRAN outer_tx\nGO\n"
                + "BEGIN TRAN\nGO\nINSERT INTO TARGET_ROWS VALUES (2)\nGO\nROLLBACK TRAN\nGO\n");
    }

    private void runScript(String script) throws Exception {
        IPlugin previous = Chat2DBContext.PLUGIN_MAP.put("SQLSERVER", new SqlServerPlugin());
        try (Connection real = DriverManager.getConnection("jdbc:h2:mem:sqlserver_script_contract")) {
            try (Statement statement = real.createStatement()) {
                statement.execute("CREATE TABLE TARGET_ROWS(ID INT PRIMARY KEY)");
            }
            DriverShape shape = new DriverShape(real);
            ConnectInfo info = new ConnectInfo();
            info.setDbType("SQLSERVER");
            info.setDriverConfig(new DriverConfig());
            info.setConnection(shape.connection());
            Chat2DBContext.putContext(info);
            Path file = directory.resolve("script.sql");
            Files.writeString(file, script);
            new SQLImporter().run(ImportTaskSpec.builder().sourceFile(file.toString()).build(), new Context());
            assertEquals(0, shape.jdbcCommits, "batch executor must not commit a script-owned transaction");
            try (Statement statement = real.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM TARGET_ROWS")) {
                rows.next();
                assertEquals(0, rows.getInt(1), "the final outer rollback must remove every script row");
            }
        } finally {
            Chat2DBContext.removeContext();
            if (previous == null) Chat2DBContext.PLUGIN_MAP.remove("SQLSERVER");
            else Chat2DBContext.PLUGIN_MAP.put("SQLSERVER", previous);
        }
    }

    private static final class DriverShape {
        final Connection real;
        final Map<String, Savepoint> saves = new HashMap<>();
        int depth;
        int jdbcCommits;
        boolean jdbcAutoCommit = true;
        DriverShape(Connection real) { this.real = real; }
        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getAutoCommit": return jdbcAutoCommit;
                    case "setAutoCommit": jdbcAutoCommit = (boolean) args[0]; break;
                    case "commit": jdbcCommits++; depth = 0; break;
                    case "createStatement": return statement(real.createStatement(), null);
                    case "prepareStatement": {
                        String sql = (String) args[0];
                        String leading = sql.trim().toUpperCase(Locale.ROOT);
                        boolean control = List.of("BEGIN", "SAVE", "ROLLBACK", "COMMIT").stream().anyMatch(leading::startsWith);
                        return statement(real.prepareStatement(control ? "SELECT 1" : sql), sql);
                    }
                    default: break;
                }
                return invoke(real, method, args);
            });
        }
        Statement statement(Statement realStatement, String preparedSql) {
            Class<?> type = realStatement instanceof java.sql.PreparedStatement ? java.sql.PreparedStatement.class : Statement.class;
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                String sql = args != null && args.length > 0 && args[0] instanceof String value ? value : preparedSql;
                if (("execute".equals(method.getName()) || "executeQuery".equals(method.getName())) && sql != null) {
                    String normalized = sql.trim().replaceAll(";+\\s*$", "").toUpperCase(Locale.ROOT);
                    String[] words = normalized.split("\\s+");
                    if ("SELECT @@TRANCOUNT".equals(normalized)) return realStatement.executeQuery("SELECT " + depth);
                    if (normalized.startsWith("BEGIN TRAN")) {
                        if (depth++ == 0) real.setAutoCommit(false);
                        return false;
                    }
                    if (normalized.startsWith("SAVE TRAN")) {
                        saves.put(words[2], real.setSavepoint());
                        return false;
                    }
                    if (normalized.startsWith("ROLLBACK")) {
                        Savepoint saved = words.length > 2 ? saves.get(words[2]) : null;
                        if (saved != null) real.rollback(saved);
                        else { real.rollback(); depth = 0; real.setAutoCommit(true); saves.clear(); }
                        return false;
                    }
                    if (normalized.startsWith("COMMIT")) {
                        if (--depth == 0) { real.commit(); real.setAutoCommit(true); }
                        return false;
                    }
                }
                return invoke(realStatement, method, args);
            });
        }
        static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
    }

    private static final class Context implements TaskExecutionContext {
        public void reportProgress(int progress, String stage, String message) { }
        public void logInfo(String code, String message) { }
        public void logInfo(String code, String message, Map<String,Object> details) { }
        public void logWarn(String code, String message, Map<String,Object> details) { }
        public void logError(String code, String message, Map<String,Object> details) { }
        public void checkCancelled() { }
        public void registerCancelable(TaskCancelable resource) { }
        public ArtifactDraft createArtifact(String path, String name, String mediaType) { throw new UnsupportedOperationException(); }
        public void write(String content) { throw new UnsupportedOperationException(); }
        public void onStatementCreated(Statement statement) { }
        public void onStatementClosed(Statement statement) { }
    }
}
