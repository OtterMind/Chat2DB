package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.AdaptiveBatchSizer;
import ai.chat2db.community.domain.core.impl.task.AdaptiveConcurrencyGate;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Buffers parsed CSV rows for parallel JDBC writes. Queues and batches are bounded; each worker
 * owns its connection and inherits the task's statement guard. Failure cancels outstanding work
 * and cleanup waits for all workers before the task can finish.
 */
@Slf4j
public final class ImportRowBatcher implements AutoCloseable {

    /** Initial batch size; the adaptive sizer caps growth at 50,000 rows. */
    private static final int FAST_MODE_BATCH_ROWS = 20_000;

    private static final int QUEUE_CAPACITY = 4;

    // About 4 MiB of UTF-16 SQL text per batch. A single larger row is sent alone.
    private static final long MAX_BATCH_CHARS = 2L * 1024 * 1024;

    /** Contract baseline fan-out of the fast mode; the adaptive gate grows it further on demand. */
    private static final int BASE_WORKERS = 4;

    private final TaskExecutionContext context;

    private final ConnectInfo connectInfo;

    private final Context requestContext;

    private final Consumer<String> statementGuard;

    private final Map<String, String> loggingContext;

    private long bufferedChars;

    private long reportedRows;

    private final AdaptiveBatchSizer batchSizer;

    private final LongAdder importedCount = new LongAdder();

    private final List<String> bufferedSqls = new ArrayList<>(FAST_MODE_BATCH_ROWS);

    private long firstBufferedRow;

    // --- parallel-execution state, null on the serial path ---
    private volatile int workerCount;

    private final List<BlockingQueue<PendingBatch>> queues;

    private final ExecutorService workerPool;

    private final Object workerGrowthLock = new Object();

    private final AtomicBoolean closing = new AtomicBoolean();

    private final AdaptiveConcurrencyGate gate;

    private final AtomicBoolean aborted = new AtomicBoolean();

    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private final AtomicInteger inFlightBatches = new AtomicInteger();

    private final AtomicInteger peakInFlightBatches = new AtomicInteger();

    private final Object quiesceMonitor = new Object();

    private long submittedBatches;

    private final long createdNanos = System.nanoTime();

    private volatile long totalImportNanos;

    public ImportRowBatcher(TaskExecutionContext context) {
        this(context, machineThreadCeiling(), runnable -> {
            Thread thread = new Thread(runnable, "chat2db-import-" + context.taskId());
            thread.setDaemon(true);
            return thread;
        });
    }

    ImportRowBatcher(TaskExecutionContext context, int maxWorkers, ThreadFactory threadFactory) {
        this.context = context;
        this.connectInfo = Chat2DBContext.getConnectInfo();
        this.requestContext = ContextUtils.queryContext();
        this.statementGuard = Chat2DBContext.captureStatementGuard();
        this.loggingContext = MDC.getCopyOfContextMap();
        this.batchSizer = new AdaptiveBatchSizer(FAST_MODE_BATCH_ROWS);
        int requestedWorkers = effectiveWorkerCount(connectInfo, maxWorkers);
        List<BlockingQueue<PendingBatch>> builtQueues = null;
        AdaptiveConcurrencyGate builtGate = null;
        ExecutorService builtPool = null;
        if (requestedWorkers > 1) {
            try {
                builtQueues = new CopyOnWriteArrayList<>();
                for (int index = 0; index < requestedWorkers; index++) {
                    builtQueues.add(new ArrayBlockingQueue<>(QUEUE_CAPACITY));
                }
                builtGate = AdaptiveConcurrencyGate.create(requestedWorkers, maxWorkers);
                builtPool = Executors.newCachedThreadPool(threadFactory);
            } catch (Throwable parallelStartupFailure) {
                // Keep fast-mode batching on the calling thread if parallel infrastructure fails.
                log.warn("Parallel import infrastructure failed to start; degrading to serial execution",
                        parallelStartupFailure);
                if (builtPool != null) {
                    builtPool.shutdownNow();
                }
                builtQueues = null;
                builtGate = null;
                builtPool = null;
                requestedWorkers = 1;
            }
        }
        this.workerCount = requestedWorkers;
        this.queues = builtQueues;
        this.gate = builtGate;
        this.workerPool = builtPool;
        if (this.workerPool != null) {
            try {
                for (int index = 0; index < this.workerCount; index++) {
                    int workerIndex = index;
                    this.workerPool.execute(() -> runWorker(workerIndex));
                }
            } catch (RuntimeException | Error startupFailure) {
                this.workerPool.shutdownNow();
                boolean interrupted = false;
                while (!this.workerPool.isTerminated()) {
                    try {
                        this.workerPool.awaitTermination(200L, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                        this.workerPool.shutdownNow();
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw startupFailure;
            }
        }
    }

    public void accept(long fileRowNumber, String sql) {
        try {
            acceptRow(fileRowNumber, sql);
        } catch (RuntimeException | Error taskFailure) {
            recordFailure(taskFailure);
            throw taskFailure;
        }
    }

    private void acceptRow(long fileRowNumber, String sql) {
        context.checkCancelled();
        throwIfFailed();
        if (!bufferedSqls.isEmpty() && bufferedChars + sql.length() > MAX_BATCH_CHARS) {
            flushBufferedBatch();
        }
        if (bufferedSqls.isEmpty()) {
            firstBufferedRow = fileRowNumber;
        }
        bufferedSqls.add(sql);
        bufferedChars += sql.length();
        if (bufferedSqls.size() >= batchSizer.batchSize() || bufferedChars >= MAX_BATCH_CHARS) {
            flushBufferedBatch();
        }
    }

    public long importedRows() {
        return importedCount.sum();
    }

    /** Final adaptive batch size; observability for tests and ops dashboards. */
    public int finalBatchSize() {
        return batchSizer.batchSize();
    }

    /** Available permits of the adaptive gate at call time (1 on the serial path). */
    public int gatePermits() {
        return gate == null ? 1 : gate.availablePermits();
    }

    /** Wall time of the import measured in {@link #close()}; 0 before the first close. */
    public long elapsedNanos() {
        return totalImportNanos;
    }

    /**
     * Final adaptive state of the most recently closed batcher. A process-wide snapshot because
     * callers that drive the importer through {@code CSVImporter} never hold the instance; the
     * last closed batcher wins when several imports run at once.
     */
    public record ImportTuningSnapshot(int workers, long batches, long rows, long nanos,
            int batchSize, int gatePermits, int peakInFlightBatches) { }

    private static final AtomicReference<ImportTuningSnapshot> LAST_TUNING = new AtomicReference<>();

    public static ImportTuningSnapshot lastTuningSnapshot() {
        return LAST_TUNING.get();
    }

    /**
     * Executes whatever is buffered; called at end of stream and whenever the caller needs a sync
     * point. In parallel mode this waits until every submitted batch finished.
     */
    public void flush() {
        try {
            context.checkCancelled();
            throwIfFailed();
            flushBufferedBatch();
            if (workerPool != null) {
                awaitQuiesce();
            }
        } catch (RuntimeException | Error taskFailure) {
            recordFailure(taskFailure);
            throw taskFailure;
        }
    }

    /** Submits the current buffer without turning normal producer flow into a global barrier. */
    private void flushBufferedBatch() {
        if (bufferedSqls.isEmpty()) {
            return;
        }
        PendingBatch batch = new PendingBatch(List.copyOf(bufferedSqls), submittedBatches, firstBufferedRow);
        submittedBatches++;
        bufferedSqls.clear();
        bufferedChars = 0;
        executeBatch(batch);
    }

    private void executeBatch(PendingBatch batch) {
        if (workerPool != null) {
            submitBatch(batch);
        } else {
            executePendingBatch(batch);
        }
    }

    /**
     * Executes a finished batch in the calling (serial) or a worker (parallel) context and reports
     * the measured cost to the adaptive sizer and gate.
     */
    private void executePendingBatch(PendingBatch batch) {
        long started = System.nanoTime();
        int rows = batch.sqls().size();
        try {
            DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(
                    Chat2DBContext.getConnection(), batch.sqls(), context, this::checkActive);
            long elapsed = System.nanoTime() - started;
            if (gate != null) {
                gate.record(rows, elapsed);
            }
            batchSizer.record(rows, elapsed);
            reportBatchSuccess(batch);
        } catch (RuntimeException | Error batchFailure) {
            // Publish failure before decrementing in-flight work, so flush cannot report success.
            recordFailure(batchFailure);
            if (!(batchFailure instanceof TaskCancelledException)) {
                context.logError("IMPORT_BATCH_FAILED", "Could not import batch", Map.of(
                        "statementCount", rows,
                        "firstRow", batch.firstRowNumber(),
                        "message", StringUtils.defaultString(batchFailure.getMessage())));
            }
            throw batchFailure;
        } finally {
            if (workerPool != null) {
                batchCompleted();
            }
        }
    }

    private synchronized void reportBatchSuccess(PendingBatch batch) {
        importedCount.add(batch.sqls().size());
        long rows = importedCount.sum();
        if (rows > reportedRows) {
            context.reportProgress((int) (20 + Math.min(70L, rows / 100)), TaskStage.IMPORTING.name(),
                    "Imported " + rows + " rows");
            reportedRows = rows;
        }
        context.logInfo("BATCH_EXECUTED", "SQL batch executed",
                Map.of("batch", batch.seq() + 1, "statementCount", batch.sqls().size(), "importedRows", rows));
    }

    private static int machineThreadCeiling() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static int effectiveWorkerCount(ConnectInfo connectInfo, int maxWorkers) {
        if (StringUtils.isBlank(connectInfo.getUrl())) {
            return 1;
        }
        return Math.min(BASE_WORKERS, maxWorkers);
    }

    /**
     * Grows the live worker set to match the adaptive gate: once the AIMD tuning admits more
     * concurrent batches than there are workers, another queue/worker pair is added up to the
     * configured ceiling.
     */
    private void ensureWorkerCapacity() {
        AdaptiveConcurrencyGate liveGate = gate;
        if (liveGate == null || closing.get()) {
            return;
        }
        int target = liveGate.totalPermits();
        synchronized (workerGrowthLock) {
            if (closing.get()) {
                return;
            }
            while (workerCount < target) {
                int index = workerCount;
                queues.add(new ArrayBlockingQueue<>(QUEUE_CAPACITY));
                workerPool.execute(() -> runWorker(index));
                workerCount = index + 1;
            }
        }
    }

    private void submitBatch(PendingBatch batch) {
        throwIfFailed();
        ensureWorkerCapacity();
        int inFlight = inFlightBatches.incrementAndGet();
        peakInFlightBatches.accumulateAndGet(inFlight, Math::max);
        BlockingQueue<PendingBatch> queue = queues.get((int) (batch.seq() % workerCount));
        try {
            // Bounded offer with failure checks: when every worker died there is nobody left to
            // drain the queues, and a blocking put would hang the import forever.
            while (!queue.offer(batch, 200L, TimeUnit.MILLISECONDS)) {
                throwIfFailed();
                context.checkCancelled();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inFlightBatches.decrementAndGet();
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), "Import was interrupted");
        }
    }

    private void batchCompleted() {
        if (inFlightBatches.decrementAndGet() == 0) {
            synchronized (quiesceMonitor) {
                quiesceMonitor.notifyAll();
            }
        }
    }

    private void awaitQuiesce() {
        synchronized (quiesceMonitor) {
            while (inFlightBatches.get() > 0) {
                checkActive();
                try {
                    quiesceMonitor.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Import was interrupted");
                }
            }
        }
        throwIfFailed();
    }

    private void runWorker(int workerIndex) {
        Thread.currentThread().setName("chat2db-import-" + context.taskId() + "-" + workerIndex);
        // Created on first use and owned by this worker until it exits; workers never borrow
        // or return pooled connections.
        ConnectInfo isolated = null;
        try (var ignored = Chat2DBContext.bindStatementGuard(statementGuard)) {
            isolated = connectInfo.copy();
            Chat2DBContext.putContext(isolated);
            ContextUtils.setContext(requestContext);
            if (loggingContext != null) {
                MDC.setContextMap(loggingContext);
            }
            while (true) {
                PendingBatch batch = queues.get(workerIndex).take();
                if (batch == END_OF_QUEUE) {
                    return;
                }
                gate.awaitPermit(this::checkActive);
                try {
                    checkActive();
                    if (isolated.getConnection() == null) {
                        ConnectionPool.createNewConnection(isolated);
                    }
                    executePendingBatch(batch);
                } finally {
                    gate.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(new TaskCancelledException());
        } catch (Throwable t) {
            recordFailure(t);
        } finally {
            try {
                if (isolated != null) {
                    // Worker connections are dedicated: close them and never return them to a pool.
                    try {
                        isolated.close();
                    } finally {
                        isolated.setConnection(null);
                    }
                }
            } finally {
                Chat2DBContext.removeContext();
                ContextUtils.removeContext();
                MDC.clear();
            }
        }
    }

    private void recordFailure(Throwable taskFailure) {
        boolean firstFailure = failure.compareAndSet(null, taskFailure);
        aborted.set(true);
        if (firstFailure) {
            context.cancelResources();
        }
        synchronized (quiesceMonitor) {
            quiesceMonitor.notifyAll();
        }
    }

    private void checkActive() {
        throwIfFailed();
        context.checkCancelled();
    }

    private void throwIfFailed() {
        if (aborted.get()) {
            Throwable cause = failure.get();
            throw cause instanceof RuntimeException runtime ? runtime
                    : new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Import failed", cause);
        }
    }

    private static final PendingBatch END_OF_QUEUE =
            new PendingBatch(List.of(), -1L, Long.MAX_VALUE);

    private record PendingBatch(List<String> sqls, long seq, long firstRowNumber) {
    }

    /** Stops pending writes when the source parser fails outside the batch executor. */
    public void abort(Throwable sourceFailure) {
        recordFailure(sourceFailure);
    }

    @Override
    public void close() {
        Throwable existingFailure = failure.get();
        try {
            if (existingFailure == null) {
                flush();
            }
        } finally {
            if (workerPool != null) {
                closing.set(true);
                if (aborted.get()) {
                    workerPool.shutdownNow();
                } else {
                    for (BlockingQueue<PendingBatch> queue : queues) {
                        queue.add(END_OF_QUEUE); // Successful flush drained all submitted work.
                    }
                    workerPool.shutdown();
                }
                boolean interrupted = Thread.interrupted();
                try {
                    while (!workerPool.isTerminated()) {
                        try {
                            workerPool.awaitTermination(200L, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            interrupted = true;
                            recordFailure(new TaskCancelledException());
                            workerPool.shutdownNow();
                        }
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            totalImportNanos = System.nanoTime() - createdNanos;
            long importedRows = importedCount.sum();
            double seconds = totalImportNanos / 1_000_000_000.0D;
            long rowsPerSecond = seconds > 0 ? (long) (importedRows / seconds) : 0L;
            // Final adaptive state: how far the AIMD gate grew/shrank and where the batch sizer
            // settled, for production observability and stress-test reporting.
            log.info("Import batcher finished: workers={}, batches={}, imported rows={}, "
                            + "in {}s -> {} rows/s, final batch size={}, "
                            + "final gate permits={}",
                    workerCount, submittedBatches, importedRows,
                    Math.round(seconds), rowsPerSecond,
                    batchSizer.batchSize(), gate == null ? 1 : gate.availablePermits());
            LAST_TUNING.set(new ImportTuningSnapshot(workerCount, submittedBatches, importedRows,
                    totalImportNanos, batchSizer.batchSize(),
                    gate == null ? 1 : gate.availablePermits(), peakInFlightBatches.get()));
        }
    }
}
