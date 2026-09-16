package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.*;
import ai.chat2db.community.domain.api.model.task.extension.TaskOperation;
import ai.chat2db.community.domain.api.model.task.extension.TaskStatementContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.api.service.task.extension.ITaskExecutionGuard;
import ai.chat2db.community.domain.core.impl.task.extension.TaskExtensionManager;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ParallelImportLifecycleTest {
    private static final String TYPE = "PARALLEL_IMPORT_LIFECYCLE_TEST";
    private static final long DATASOURCE_ID = 92908001L;
    @TempDir Path directory;
    private Connection observer;
    private ConnectInfo connectInfo;
    private Context requestContext;
    private IPlugin previousPlugin;
    private final List<Connection> opened = new CopyOnWriteArrayList<>();
    private final List<TaskEvent> events = new CopyOnWriteArrayList<>();
    private final List<TaskProgress> progress = new CopyOnWriteArrayList<>();
    private final List<Long> batchChars = new CopyOnWriteArrayList<>();
    private final List<Integer> batchRows = new CopyOnWriteArrayList<>();
    private final AtomicInteger closeCalls = new AtomicInteger();
    private final AtomicInteger executeCalls = new AtomicInteger();
    private final CountDownLatch blocked = new CountDownLatch(1);
    private final CountDownLatch cancelled = new CountDownLatch(1);
    private final CountDownLatch driverExited = new CountDownLatch(1);
    private final CountDownLatch releaseDriver = new CountDownLatch(1);
    private volatile boolean failWithBlockedPeer;
    private volatile boolean blockUntilCancellation;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:parallel_lifecycle_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        observer = DriverManager.getConnection(url);
        try (Statement statement = observer.createStatement()) {
            statement.execute("CREATE TABLE ROWS_TARGET (ID INT PRIMARY KEY, NAME VARCHAR(10000))");
        }
        requestContext = new Context();
        ContextUtils.setContext(requestContext);
        MDC.put("taskId", "review-task");
        connectInfo = new ConnectInfo();
        connectInfo.setDbType(TYPE);
        connectInfo.setDataSourceId(DATASOURCE_ID);
        connectInfo.setUrl(url);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(observer);
        DBConfig config = new DBConfig();
        config.setDbType(TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TYPE, new IPlugin() {
            public DBConfig getDBConfig() { return config; }
            public IDbManager getDbManager() {
                return new DefaultDBManager() {
                    @Override public Connection getConnection(ConnectInfo info) {
                        assertSame(requestContext, ContextUtils.queryContext());
                        assertEquals("review-task", MDC.get("taskId"));
                        try {
                            Connection real = DriverManager.getConnection(url);
                            opened.add(real);
                            return wrapConnection(real);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }
                };
            }
        });
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseDriver.countDown();
        Chat2DBContext.removeContext();
        ContextUtils.removeContext();
        MDC.clear();
        for (Connection connection : opened) connection.close();
        observer.close();
        try (Connection connection = DriverManager.getConnection(connectInfo.getUrl());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        ConnectionPool.removeConnection(DATASOURCE_ID);
        if (previousPlugin == null) Chat2DBContext.PLUGIN_MAP.remove(TYPE);
        else Chat2DBContext.PLUGIN_MAP.put(TYPE, previousPlugin);
    }

    @Test
    void successClosesDedicatedConnectionsAndReportsMonotonicProgress() throws Exception {
        run(spec(40_001, "ok"), task(1L));

        assertEquals(40_001, countRows());
        assertTrue(opened.size() >= 2);
        assertConnectionsClosed();
        List<TaskProgress> updates = progress.stream().filter(p -> "IMPORTING".equals(p.getStage())).toList();
        assertFalse(updates.isEmpty());
        long lastRows = 0;
        int lastProgress = 20;
        for (TaskProgress update : updates) {
            long rows = Long.parseLong(update.getMessage().split(" ")[1]);
            assertTrue(rows > lastRows);
            assertTrue(update.getProgress() >= lastProgress);
            lastRows = rows;
            lastProgress = update.getProgress();
        }
        assertEquals(40_001, lastRows);
        long cumulativeRows = 0;
        for (TaskEvent event : events) {
            if ("BATCH_EXECUTED".equals(event.getCode())) {
                cumulativeRows += ((Number) event.getDetails().get("statementCount")).longValue();
                assertEquals(cumulativeRows, ((Number) event.getDetails().get("importedRows")).longValue());
            }
        }
        assertEquals(40_001, cumulativeRows);
        TaskEvent summary = events.stream().filter(e -> "IMPORT_SUMMARY".equals(e.getCode())).findFirst().orElseThrow();
        assertEquals(40_001L, ((Number) summary.getDetails().get("importedRows")).longValue());
        assertTrue(((Number) summary.getDetails().get("elapsedMillis")).longValue() >= 0);
        assertEquals(90, lastProgress);
        var pool = ConnectionPool.class.getDeclaredField("CONNECTION_MAP");
        pool.setAccessible(true);
        assertFalse(((Map<?, ?>) pool.get(null)).containsKey(DATASOURCE_ID), "dedicated workers must not create pool queues");
    }

    @Test
    void taskStatementGuardRunsOnWorkersBeforeAnyWrite() throws Exception {
        AtomicInteger taskCalls = new AtomicInteger();
        AtomicInteger guardCalls = new AtomicInteger();
        var capturedTask = new ai.chat2db.community.domain.api.model.task.extension.TaskExecutionContext(
                2L, TaskType.DATA_FILE_IMPORT, null, null, null, List.of("ROWS_TARGET"), TaskOperation.IMPORT);
        TaskExtensionManager manager = new TaskExtensionManager(List.of(), List.of(new ITaskExecutionGuard() {
            public void beforeTask(ai.chat2db.community.domain.api.model.task.extension.TaskExecutionContext context) {
                assertSame(capturedTask, context);
                taskCalls.incrementAndGet();
            }
            public void beforeStatement(TaskStatementContext context) {
                guardCalls.incrementAndGet();
                assertSame(capturedTask, context.getTaskContext());
                assertSame(requestContext, ContextUtils.queryContext());
                assertEquals("review-task", MDC.get("taskId"));
                throw new IllegalStateException("statement denied");
            }
        }));
        ImportTaskSpec spec = spec(10, "ok");

        manager.runGuarded(capturedTask, () -> {
            try (var ignored = Chat2DBContext.bindStatementGuard(manager.captureStatementGuard())) {
                assertThrows(RuntimeException.class, () -> run(spec, task(2L)));
            }
        });

        assertEquals(1, taskCalls.get());
        assertTrue(guardCalls.get() > 0);
        assertEquals(0, executeCalls.get());
        assertEquals(0, countRows());
        assertConnectionsClosed();
    }

    @Test
    void batchFailureCancelsPeerAndWaitsForItsJdbcCallToExit() throws Exception {
        failWithBlockedPeer = true;
        ImportTaskSpec spec = spec(40_000, "ok");
        RunningTask task = task(3L);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> runOnCallerThread(spec, task));
            assertTrue(cancelled.await(10, TimeUnit.SECONDS), "failure must cancel the blocked JDBC statement");
            assertFalse(future.isDone(), "task must remain active until the driver exits");
            assertFalse(driverExited.await(300, TimeUnit.MILLISECONDS));
            assertFalse(task.cancellationToken().isCancelled(), "an import error must remain a failure");

            releaseDriver.countDown();
            assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertTrue(driverExited.await(1, TimeUnit.SECONDS));
            assertConnectionsClosed();
            assertEquals(0, countRows());
        } finally {
            releaseDriver.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void userCancellationWaitsForTheWorkerEvenWhenCallerIsInterrupted() throws Exception {
        blockUntilCancellation = true;
        ImportTaskSpec spec = spec(10, "ok");
        RunningTask task = task(4L);
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch callerExited = new CountDownLatch(1);
        FutureTask<Void> future = new FutureTask<>(() -> {
            try { runOnCallerThread(spec, task); }
            finally { callerExited.countDown(); }
            return null;
        });
        task.setFuture(future);
        try {
            executor.execute(future);
            assertTrue(blocked.await(10, TimeUnit.SECONDS));
            assertTrue(task.requestCancellation(true));
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertFalse(callerExited.await(300, TimeUnit.MILLISECONDS));

            releaseDriver.countDown();
            assertTrue(callerExited.await(10, TimeUnit.SECONDS));
            assertConnectionsClosed();
            assertEquals(0, countRows());
        } finally {
            releaseDriver.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void wideRowsFlushBeforeTheRowCountThreshold() throws Exception {
        run(spec(600, "x".repeat(4096)), task(5L));

        assertEquals(600, countRows());
        assertTrue(batchRows.size() >= 2, "SQL text size must bound a batch before 20,000 rows");
        assertEquals(600, batchRows.stream().mapToInt(Integer::intValue).sum());
        assertTrue(batchChars.stream().allMatch(chars -> chars <= 2L * 1024 * 1024));
        assertConnectionsClosed();
    }

    private RunningTask task(long id) { return new RunningTask(id); }

    private void run(ImportTaskSpec spec, RunningTask task) {
        TaskStorage storage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskStorage.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("updateProgressIfRunning")) { progress.add((TaskProgress) args[1]); return true; }
                    if (method.getName().equals("appendEvent")) { events.add((TaskEvent) args[0]); return args[0]; }
                    throw new UnsupportedOperationException(method.getName());
                });
        new CSVImporter().run(spec, new TaskExecutionContextImpl(task.taskId(), task, storage, new ArtifactServiceImpl()));
    }

    private void runOnCallerThread(ImportTaskSpec spec, RunningTask task) {
        Chat2DBContext.putContext(connectInfo);
        ContextUtils.setContext(requestContext);
        MDC.put("taskId", "review-task");
        try { run(spec, task); }
        finally { ContextUtils.removeContext(); MDC.clear(); Chat2DBContext.removeContext(); }
    }

    private ImportTaskSpec spec(int count, String name) throws Exception {
        Path csv = directory.resolve("rows.csv");
        try (var writer = Files.newBufferedWriter(csv)) {
            writer.write("ID,NAME\n");
            for (int id = 1; id <= count; id++) writer.write(id + "," + name + "\n");
        }
        return ImportTaskSpec.builder().sourceFile(csv.toString()).format("CSV").mode("FAST")
                .target(TaskTargetSnapshot.builder().tableName("ROWS_TARGET").build())
                .columnMappings(List.of(new ImportColumnMapping("ID", "ID"), new ImportColumnMapping("NAME", "NAME"))).build();
    }

    private Connection wrapConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("close")) closeCalls.incrementAndGet();
            try {
                Object value = method.invoke(connection, args);
                return method.getName().equals("createStatement") ? wrapStatement((Statement) value) : value;
            } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    private Statement wrapStatement(Statement statement) {
        AtomicInteger rows = new AtomicInteger();
        AtomicLong chars = new AtomicLong();
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "addBatch" -> { rows.incrementAndGet(); chars.addAndGet(((String) args[0]).length()); }
                case "cancel" -> { cancelled.countDown(); return null; }
                case "executeBatch" -> {
                    int execution = executeCalls.incrementAndGet();
                    batchChars.add(chars.get());
                    batchRows.add(rows.get());
                    if (blockUntilCancellation || failWithBlockedPeer && execution == 1) {
                        blocked.countDown();
                        boolean interrupted = false;
                        try {
                            while (true) {
                                try { releaseDriver.await(); break; }
                                catch (InterruptedException e) { interrupted = true; }
                            }
                            throw new SQLException("driver acknowledged cancellation");
                        } finally {
                            driverExited.countDown();
                            if (interrupted) Thread.currentThread().interrupt();
                        }
                    }
                    if (failWithBlockedPeer) {
                        if (!blocked.await(5, TimeUnit.SECONDS)) throw new AssertionError("peer did not start");
                        throw new SQLException("injected batch failure");
                    }
                }
            }
            try { return method.invoke(statement, args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    private int countRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(connectInfo.getUrl());
             Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM ROWS_TARGET")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private void assertConnectionsClosed() throws Exception {
        assertFalse(opened.isEmpty());
        for (Connection connection : opened) assertTrue(connection.isClosed());
        assertEquals(opened.size(), closeCalls.get(), "each connection must be closed exactly once");
    }
}
