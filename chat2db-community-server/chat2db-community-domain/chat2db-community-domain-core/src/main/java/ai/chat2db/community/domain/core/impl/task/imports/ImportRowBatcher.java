package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.AdaptiveBatchSizer;
import ai.chat2db.community.domain.core.impl.task.AdaptiveConcurrencyGate;
import ai.chat2db.community.domain.core.impl.task.TaskResumeJournal;
import ai.chat2db.community.domain.core.impl.task.imports.ImportColumnResolver.Resolution;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;
import java.util.concurrent.atomic.LongAdder;

/**
 * Turns file rows into buffered {@code INSERT} statements and executes them in JDBC batches.
 * With {@code onError=SKIP} a failing row is retried individually and recorded in a
 * {@code REJECT}-role NDJSON sub-artifact instead of aborting the task.
 *
 * <p>Parallel execution: the worker count resolves from the
 * {@code chat2db.task.import.parallelism} system property ({@code 0}, the default, picks the
 * adaptive band {@code [2, min(16, CPU cores)]}, {@code 1} forces the serial path, and explicit
 * values are clamped into the band), finished batches are handed to
 * partitioned queues so batch {@code n} is always executed before batch {@code n + workerCount}:
 * per worker the order is strict, while workers run in parallel. The number of <em>active</em>
 * workers and the batch size are self-tuning (see {@link AdaptiveConcurrencyGate} and
 * {@link AdaptiveBatchSizer}), so the pipeline converges to the throughput the target database
 * actually sustains. Rows have no ordering constraints, so inter-worker interleaving is safe; the
 * only visible effect is that auto-generated key values may interleave across workers.
 */
@Slf4j
public final class ImportRowBatcher implements AutoCloseable {

    private static final int DEFAULT_BATCH_ROWS = 500;

    private static final int QUEUE_CAPACITY = 4;

    /** Upper bound of the adaptive worker band, also capped by the machine's CPU count. */
    private static final int MAX_WORKERS = 16;

    /** How long a worker waits for an adaptive gate permit before degrading to ungated execution. */
    private static final long GATE_WAIT_MILLIS = 30_000L;

    private static final String RESUME_KIND_IMPORT = "IMPORT_WATERMARK";

    private static final String ON_ERROR_SKIP = "SKIP";

    private static final String REJECT_ROLE = "REJECT";

    private final ImportTaskSpec spec;

    private final TaskExecutionContext context;

    private final Resolution resolution;

    private final ImportOptions options;

    private final IValueProcessor valueProcessor;

    private final ISqlBuilder sqlBuilder;

    private final ConnectInfo connectInfo;

    private final ImportSqlExecutor sqlExecutor;

    private final AdaptiveBatchSizer batchSizer = new AdaptiveBatchSizer(DEFAULT_BATCH_ROWS);

    private final LongAdder importedCount = new LongAdder();

    private final Object rejectLock = new Object();

    private final List<String> bufferedSqls = new ArrayList<>(DEFAULT_BATCH_ROWS);

    private final List<String> bufferedRows = new ArrayList<>(DEFAULT_BATCH_ROWS);

    private final List<Long> bufferedRowNumbers = new ArrayList<>(DEFAULT_BATCH_ROWS);

    private BufferedWriter rejectWriter;

    private long rejectedRowCount;

    // --- parallel-execution state, null on the serial path ---
    private final int workerCount;

    private final List<BlockingQueue<PendingBatch>> queues;

    private final ExecutorService workerPool;

    private final AdaptiveConcurrencyGate gate;

    private final AtomicBoolean aborted = new AtomicBoolean();

    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private final AtomicInteger inFlightBatches = new AtomicInteger();

    private final AtomicInteger peakInFlightBatches = new AtomicInteger();

    private final Object quiesceMonitor = new Object();

    private long submittedBatches;

    private final long createdNanos = System.nanoTime();

    private volatile long totalImportNanos;

    private final AtomicBoolean ungatedWarned = new AtomicBoolean();

    // --- three-layer resume state: rows below the durable watermark are committed ---------
    private final Map<String, Object> sourceIdentity;

    private final long resumeBelowRow;

    private final TaskResumeJournal journal;

    /** Standard mode: serial path with a fixed batch size (see {@link TaskExecutionMode}). */
    private final boolean standardMode;

    /** Batch sequence -> first row number, for submitted-but-not-durable batches. */
    private final ConcurrentSkipListMap<Long, Long> inFlightFirstRows =
            new ConcurrentSkipListMap<>();

    private long lastAcceptedRow;

    private long bufferedFirstRow = Long.MAX_VALUE;

    private long batchesSinceCheckpoint;

    // Intervals are read per construction (not class-load) so tests can retune them reliably.
    /** Batch interval of the Layer-1 journal progress records. */
    private final int journalProgressInterval =
            Integer.getInteger("chat2db.task.import.journal-interval", 8);

    /** Batch interval of the Layer-2 task-storage checkpoints. */
    private final int checkpointInterval =
            Integer.getInteger("chat2db.task.import.checkpoint-interval", 64);

    /** Batch interval of the Layer-3 committed-snapshot generations. */
    private final int snapshotInterval =
            Integer.getInteger("chat2db.task.import.snapshot-interval", 256);

    public ImportRowBatcher(ImportTaskSpec spec, TaskExecutionContext context, Resolution resolution,
            IValueProcessor valueProcessor) {
        this.spec = spec;
        this.context = context;
        this.resolution = resolution;
        this.options = spec.getOptions() == null ? new ImportOptions() : spec.getOptions();
        this.valueProcessor = valueProcessor;
        this.sqlBuilder = Chat2DBContext.getSqlBuilder();
        this.connectInfo = Chat2DBContext.getConnectInfo();
        this.standardMode = !TaskExecutionMode.isUltraFast(spec.getMode());
        this.sqlExecutor = new ImportSqlExecutor(context);
        this.sourceIdentity = sourceIdentity(spec);
        this.resumeBelowRow = resolveResumeBelowRow(spec, context);
        if (resumeBelowRow > 0) {
            log.info("Import resume: the first {} rows are durable from the interrupted run; "
                    + "they will be skipped", resumeBelowRow);
        }
        this.journal = TaskResumeJournal.open(context.taskId(), sourceIdentity);
        if (journal != null && resumeBelowRow > 0) {
            journal.progress("RESUMED", resumeBelowRow);
        }
        int requestedWorkers = standardMode ? 1 : effectiveWorkerCount(connectInfo);
        List<BlockingQueue<PendingBatch>> builtQueues = null;
        AdaptiveConcurrencyGate builtGate = null;
        ExecutorService builtPool = null;
        if (requestedWorkers > 1) {
            try {
                builtQueues = new ArrayList<>(requestedWorkers);
                for (int index = 0; index < requestedWorkers; index++) {
                    builtQueues.add(new ArrayBlockingQueue<>(QUEUE_CAPACITY));
                }
                builtGate = AdaptiveConcurrencyGate.create(2, requestedWorkers);
                builtPool = Executors.newFixedThreadPool(requestedWorkers, runnable -> {
                    Thread thread = new Thread(runnable, "chat2db-import-" + context.taskId());
                    thread.setDaemon(true);
                    return thread;
                });
            } catch (Throwable parallelStartupFailure) {
                // Adaptive parallel plumbing must never block the import: fall back to the exact
                // serial path, which stays fully supported.
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
            for (int index = 0; index < this.workerCount; index++) {
                int workerIndex = index;
                this.workerPool.execute(() -> runWorker(workerIndex));
            }
        }
        warnIfSelfReferencing(spec, this.workerCount > 1);
    }

    public void accept(long fileRowNumber, List<String> fileValues) {
        try {
            acceptRow(fileRowNumber, fileValues);
        } catch (RuntimeException taskFailure) {
            recordFailure(taskFailure);
            throw taskFailure;
        }
    }

    private void acceptRow(long fileRowNumber, List<String> fileValues) {
        context.checkCancelled();
        throwIfFailed();
        if (fileRowNumber <= resumeBelowRow) {
            // Durable from the interrupted run; skipping keeps the target duplicate-free.
            return;
        }
        String sql;
        String raw;
        try {
            sql = buildInsert(fileValues);
            raw = JSON.toJSONString(fileValues);
        } catch (RuntimeException conversionFailure) {
            handleFailedRow(fileRowNumber, fileValues, conversionFailure);
            return;
        }
        bufferedSqls.add(sql);
        bufferedRows.add(raw);
        bufferedRowNumbers.add(fileRowNumber);
        if (fileRowNumber > lastAcceptedRow) {
            lastAcceptedRow = fileRowNumber;
        }
        if (bufferedFirstRow == Long.MAX_VALUE) {
            bufferedFirstRow = fileRowNumber;
        }
        if (bufferedSqls.size() >= batchSizer.batchSize()) {
            flushBufferedBatch();
        }
    }

    public long importedRows() {
        return importedCount.sum();
    }

    public long rejectedRows() {
        synchronized (rejectLock) {
            return rejectedRowCount;
        }
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
            if (workerCount > 1) {
                awaitQuiesce();
            }
        } catch (RuntimeException taskFailure) {
            recordFailure(taskFailure);
            throw taskFailure;
        }
    }

    /** Submits the current buffer without turning normal producer flow into a global barrier. */
    private void flushBufferedBatch() {
        if (bufferedSqls.isEmpty()) {
            return;
        }
        long firstRowNumber = bufferedFirstRow;
        PendingBatch batch = new PendingBatch(List.copyOf(bufferedSqls), List.copyOf(bufferedRows),
                List.copyOf(bufferedRowNumbers), submittedBatches, firstRowNumber);
        submittedBatches++;
        bufferedSqls.clear();
        bufferedRows.clear();
        bufferedRowNumbers.clear();
        bufferedFirstRow = Long.MAX_VALUE;
        executeBatch(batch);
    }

    private void executeBatch(PendingBatch batch) {
        inFlightFirstRows.put(batch.seq(), batch.firstRowNumber());
        if (workerCount > 1) {
            submitBatch(batch);
        } else {
            executeWithTolerance(batch);
        }
    }

    /**
     * Executes a finished batch in the calling (serial) or a worker (parallel) context and reports
     * the measured cost to the adaptive sizer and gate.
     */
    private void executeWithTolerance(PendingBatch batch) {
        long started = System.nanoTime();
        int rows = batch.sqls().size();
        boolean fullyHandled = false;
        try {
            try {
                sqlExecutor.executeBatch(batch.sqls());
                importedCount.add(rows);
                fullyHandled = true;
            } catch (TaskCancelledException cancellation) {
                throw cancellation;
            } catch (RuntimeException batchFailure) {
                context.logError("IMPORT_BATCH_FAILED", "Could not import batch", Map.of(
                        "statementCount", rows,
                        "message", StringUtils.defaultString(batchFailure.getMessage())));
                if (!isSkipMode()) {
                    throw batchFailure;
                }
                // In SKIP mode every row ends handled (imported or recorded in the reject file),
                // so the watermark may advance past the batch once the replay finishes.
                replayIndividually(batch);
                fullyHandled = true;
            }
        } finally {
            long elapsed = System.nanoTime() - started;
            if (gate != null) {
                gate.record(rows, elapsed);
            }
            if (!standardMode) {
                batchSizer.record(rows, elapsed);
            }
            if (fullyHandled) {
                // Removed only on full handling: a failed batch keeps its rows un-durable, so the
                // watermark must stay below it or a resume would skip live rows.
                inFlightFirstRows.remove(batch.seq());
                maybeCheckpoint();
            }
            if (workerCount > 1) {
                batchCompleted();
            }
        }
    }

    /** Retries a failed batch row by row so genuinely bad rows can be skipped. */
    private void replayIndividually(PendingBatch batch) {
        for (int index = 0; index < batch.sqls().size(); index++) {
            try {
                sqlExecutor.executeBatch(List.of(batch.sqls().get(index)));
                importedCount.increment();
            } catch (RuntimeException rowFailure) {
                if (isConnectionFailure(rowFailure)) {
                    throw rowFailure;
                }
                handleRejectedRow(batch.rowNumbers().get(index), batch.rows().get(index),
                        rootMessage(rowFailure));
            }
        }
    }

    private static boolean isConnectionFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLNonTransientConnectionException
                    || current instanceof SQLRecoverableException
                    || current instanceof SQLTransientConnectionException) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && StringUtils.startsWith(sqlException.getSQLState(), "08")) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    // --- parallel plumbing ---------------------------------------------------------------

    /**
     * Resolves the worker count from the {@code chat2db.task.import.parallelism} system property:
     * {@code 0}, the default, picks the adaptive band ceiling {@code max(2, min(16, CPU cores))};
     * {@code 1} forces the serial path; explicit values are clamped into the [2, ceiling] band so
     * a pinned value can neither exceed the machine nor drop below the minimum fan-out. Parallel
     * workers each need their own connection, so without a JDBC url (test fixtures and
     * non-relational sources bind a prebuilt connection instead) the batcher stays serial.
     */
    private static int effectiveWorkerCount(ConnectInfo connectInfo) {
        if (StringUtils.isBlank(connectInfo.getUrl())) {
            return 1;
        }
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int ceiling = Math.max(2, Math.min(MAX_WORKERS, cores));
        int configured = Integer.getInteger("chat2db.task.import.parallelism", 0);
        if (configured == 1) {
            return 1;
        }
        if (configured > 1) {
            return Math.max(2, Math.min(ceiling, configured));
        }
        return ceiling;
    }

    private void submitBatch(PendingBatch batch) {
        throwIfFailed();
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
                throwIfFailed();
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

    /**
     * The durable watermark: every source row below it is committed. It is the first row of the
     * earliest in-flight batch (batches complete out of order), the first still-buffered row, or
     * one past the last accepted row when nothing is in flight.
     */
    private long durableWatermark() {
        long firstInFlight = inFlightFirstRows.isEmpty()
                ? Long.MAX_VALUE : inFlightFirstRows.firstEntry().getValue();
        long firstBuffered = bufferedSqls.isEmpty() ? Long.MAX_VALUE : bufferedFirstRow;
        return Math.min(firstInFlight, Math.min(firstBuffered, lastAcceptedRow + 1));
    }

    /** Layered cadence: journal every 8, storage checkpoint every 64, snapshot every 256 batches. */
    private void maybeCheckpoint() {
        batchesSinceCheckpoint++;
        long rowsDone = durableWatermark() - 1;
        try {
            if (journal != null && batchesSinceCheckpoint % journalProgressInterval == 0) {
                journal.progress("IMPORTING", rowsDone);
            }
            if (batchesSinceCheckpoint % checkpointInterval == 0) {
                context.checkpoint(ResumeState.builder()
                        .shardNo(0)
                        .kind(RESUME_KIND_IMPORT)
                        .cursorJson(resumeCursorJson(rowsDone))
                        .rowsDone(rowsDone)
                        .updatedAt(new Date())
                        .build());
            }
            if (journal != null && batchesSinceCheckpoint % snapshotInterval == 0) {
                journal.snapshot(rowsDone);
            }
        } catch (TaskCancelledException cancellation) {
            throw cancellation;
        } catch (Throwable checkpointFailure) {
            // A failed checkpoint never blocks the import; the next cadence retries.
            log.warn("Import resume checkpoint failed; continuing", checkpointFailure);
        }
    }

    private String resumeCursorJson(long rowsDone) {
        JSONObject cursor = new JSONObject();
        cursor.put("watermark", rowsDone + 1);
        cursor.putAll(sourceIdentity);
        return cursor.toJSONString();
    }

    private Map<String, Object> sourceIdentity(ImportTaskSpec spec) {
        File source = new File(StringUtils.defaultString(spec.getSourceFile()));
        Map<String, Object> identity = new HashMap<>();
        identity.put("sourcePath", source.toPath().toAbsolutePath().normalize().toString());
        identity.put("sourceLength", source.isFile() ? source.length() : -1L);
        identity.put("sourceLastModified", source.isFile() ? source.lastModified() : -1L);
        return identity;
    }

    /**
     * Resolves the resume position: the newest VALID candidate among the journal tail, the newest
     * and committed snapshot generations, and the task-storage checkpoints. A candidate whose
     * source identity no longer matches (the file was rewritten between runs) is discarded so a
     * stale checkpoint can never skip live rows.
     */
    private long resolveResumeBelowRow(ImportTaskSpec spec, TaskExecutionContext context) {
        try {
            File dir = TaskResumeJournal.directoryFor(context.taskId());
            long below = 0L;
            for (TaskResumeJournal.Snapshot candidate : Stream
                    .of(TaskResumeJournal.recoverNewest(dir), TaskResumeJournal.recoverCommitted(dir),
                            TaskResumeJournal.recoverTail(dir))
                    .flatMap(Optional::stream).toList()) {
                if (identityMatches(candidate.identity()) && candidate.rowsDone() > below) {
                    below = candidate.rowsDone();
                }
            }
            for (ResumeState state : context.resumeStates()) {
                if (state == null || !RESUME_KIND_IMPORT.equals(state.getKind())
                        || state.getRowsDone() == null || state.getRowsDone() < 0) {
                    continue;
                }
                if (identityMatches(parseIdentity(state.getCursorJson()))
                        && state.getRowsDone() > below) {
                    below = state.getRowsDone();
                }
            }
            return below;
        } catch (Throwable resumeProbeFailure) {
            log.warn("Import resume probe failed; the import restarts from the first row",
                    resumeProbeFailure);
            return 0L;
        }
    }

    private boolean identityMatches(Map<String, Object> stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        Long storedLength = asLong(stored.get("sourceLength"));
        Long storedModified = asLong(stored.get("sourceLastModified"));
        Long currentLength = asLong(sourceIdentity.get("sourceLength"));
        Long currentModified = asLong(sourceIdentity.get("sourceLastModified"));
        String storedPath = stored.get("sourcePath") instanceof String path
                ? StringUtils.trimToNull(path) : null;
        String currentPath = sourceIdentity.get("sourcePath") instanceof String path
                ? StringUtils.trimToNull(path) : null;
        return storedPath != null && storedPath.equals(currentPath)
                && storedLength != null && storedModified != null && currentLength != null
                && currentModified != null && storedLength >= 0
                && storedLength.equals(currentLength) && storedModified.equals(currentModified);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> parseIdentity(String cursorJson) {
        try {
            JSONObject cursor = JSONObject.parseObject(StringUtils.defaultString(cursorJson));
            if (cursor == null || !cursor.containsKey("sourcePath")
                    || !cursor.containsKey("sourceLength")) {
                return Map.of();
            }
            Map<String, Object> identity = new HashMap<>();
            identity.put("sourcePath", cursor.getString("sourcePath"));
            identity.put("sourceLength", cursor.getLong("sourceLength"));
            identity.put("sourceLastModified", cursor.getLong("sourceLastModified"));
            return identity;
        } catch (Exception parseFailure) {
            return Map.of();
        }
    }

    /**
     * Warns once when the target references itself (e.g. {@code category.parent_id}): parallel
     * batches carry no parent-before-child order, so an enforced self-referencing foreign key
     * needs the serial path or a deferred constraint. Purely advisory — never fails the import.
     */
    private void warnIfSelfReferencing(ImportTaskSpec spec, boolean parallel) {
        if (!parallel) {
            return;
        }
        try (ResultSet keys = Chat2DBContext.getConnection().getMetaData()
                .getImportedKeys(null, null, spec.getTarget().getTableName())) {
            while (keys.next()) {
                String referencing = keys.getString("FKTABLE_NAME");
                String referenced = keys.getString("PKTABLE_NAME");
                if (referencing != null && referencing.equalsIgnoreCase(referenced)) {
                    log.warn("Target table {} references itself; parallel batch import carries no "
                            + "parent-before-child order — if the foreign key is enforced, use the "
                            + "serial path (chat2db.task.import.parallelism=1) or defer the constraint",
                            spec.getTarget().getTableName());
                    break;
                }
            }
        } catch (Throwable probeFailure) {
            log.debug("Self-reference probe skipped", probeFailure);
        }
    }

    private void runWorker(int workerIndex) {
        Thread.currentThread().setName("chat2db-import-" + context.taskId() + "-" + workerIndex);
        // Created on first use and owned by this worker until it exits; copy() carries no
        // connection, so Chat2DBContext.getConnection() builds a dedicated one per worker.
        ConnectInfo isolated = null;
        try {
            isolated = connectInfo.copy();
            isolated.setLoginUser("task-" + context.taskId() + "#import-" + workerIndex);
            Chat2DBContext.putContext(isolated);
            while (true) {
                PendingBatch batch = queues.get(workerIndex).take();
                if (batch == END_OF_QUEUE) {
                    return;
                }
                boolean permitted = gate.admit(GATE_WAIT_MILLIS);
                if (!permitted && ungatedWarned.compareAndSet(false, true)) {
                    log.warn("Adaptive import gate did not admit within {}ms; executing batches "
                            + "ungated until the gate recovers (degraded concurrency)", GATE_WAIT_MILLIS);
                }
                try {
                    throwIfFailed();
                    executeWithTolerance(batch);
                } finally {
                    gate.relinquish(permitted);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            recordFailure(t);
        } finally {
            if (isolated != null) {
                // Hand the dedicated connection back to the pool (or close it) instead of
                // leaking it until the JVM exits.
                ConnectionPool.close(isolated);
            }
            Chat2DBContext.removeContext();
        }
    }

    private void recordFailure(Throwable taskFailure) {
        failure.compareAndSet(null, taskFailure);
        aborted.set(true);
        synchronized (quiesceMonitor) {
            quiesceMonitor.notifyAll();
        }
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
            new PendingBatch(List.of(), List.of(), List.of(), -1L, Long.MAX_VALUE);

    private record PendingBatch(List<String> sqls, List<String> rows, List<Long> rowNumbers,
            long seq, long firstRowNumber) {
    }

    // --- rejected-row bookkeeping ---------------------------------------------------------

    private void handleFailedRow(long fileRowNumber, List<String> fileValues, RuntimeException failure) {
        handleRejectedRow(fileRowNumber, JSON.toJSONString(fileValues), rootMessage(failure));
    }

    @SuppressWarnings("unused")
    private void handleFailedRowText(String rawRow, RuntimeException failure) {
        handleRejectedRow(null, rawRow, rootMessage(failure));
    }

    private void handleRejectedRow(Long fileRowNumber, String rawRow, String reason) {
        if (!isSkipMode()) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Import row failed: " + reason);
        }
        synchronized (rejectLock) {
            rejectedRowCount++;
            Integer maxErrors = options.getMaxErrors();
            if (maxErrors != null && maxErrors >= 0 && rejectedRowCount > maxErrors) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Import aborted after " + rejectedRowCount + " rejected rows");
            }
            try {
                rejectWriter().write(JSON.toJSONString(Map.of(
                        "row", fileRowNumber == null ? -1L : fileRowNumber,
                        "line", rawRow,
                        "reason", reason == null ? "unknown" : reason)));
                rejectWriter().write("\n");
            } catch (IOException e) {
                throw new UncheckedIOException("Could not write reject file", e);
            }
        }
        context.logWarn("IMPORT_ROW_REJECTED", "Import row rejected: " + reason,
                Map.of("rejectedRows", rejectedRows()));
    }

    private BufferedWriter rejectWriter() throws IOException {
        if (rejectWriter == null) {
            String fileName = StringUtils.firstNonBlank(
                    new java.io.File(StringUtils.defaultString(spec.getSourceFile())).getName(), "import")
                    + ".rejects.ndjson";
            var draft = context.createArtifact(REJECT_ROLE,
                    StringUtils.substringBeforeLast(spec.getSourceFile(), java.io.File.separator),
                    fileName, "application/x-ndjson");
            rejectWriter = Files.newBufferedWriter(draft.getTemporaryFile().toPath(), StandardCharsets.UTF_8);
        }
        return rejectWriter;
    }

    private String buildInsert(List<String> fileValues) {
        List<String> tableColumnNames = new ArrayList<>(resolution.tableColumns().size());
        List<String> values = new ArrayList<>(resolution.tableColumns().size());
        for (int index = 0; index < resolution.tableColumns().size(); index++) {
            TableColumn column = resolution.tableColumns().get(index);
            Integer sourceIndex = resolution.fileIndexes().get(index);
            String raw = sourceIndex != null && sourceIndex < fileValues.size()
                    ? fileValues.get(sourceIndex) : null;
            tableColumnNames.add(column.getName());
            values.add(toSqlLiteral(column, raw));
        }
        return sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                .databaseName(connectInfo.getDatabaseName())
                .schemaName(connectInfo.getSchemaName())
                .tableName(spec.getTarget().getTableName())
                .columnList(tableColumnNames)
                .valueList(values)
                .build());
    }

    private String toSqlLiteral(TableColumn column, String raw) {
        if (raw == null || (options.getNullString() != null && options.getNullString().equals(raw))) {
            return null;
        }
        if (raw.isEmpty()) {
            return null;
        }
        DataType dataType = new DataType();
        dataType.setDataTypeName(column.getColumnType());
        dataType.setScale(column.getDecimalDigits());
        dataType.setPrecision(column.getColumnSize());
        SQLDataValue sqlDataValue = new SQLDataValue();
        sqlDataValue.setDataType(dataType);
        sqlDataValue.setValue(raw);
        return valueProcessor.getSqlValueString(sqlDataValue);
    }

    private boolean isSkipMode() {
        return ON_ERROR_SKIP.equalsIgnoreCase(StringUtils.trimToEmpty(options.getOnError()));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    /** Stops pending writes when the source parser fails outside the batch executor. */
    public void abort(RuntimeException sourceFailure) {
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
                for (BlockingQueue<PendingBatch> queue : queues) {
                    while (!queue.offer(END_OF_QUEUE)) {
                        if (aborted.get()) {
                            break;
                        }
                    }
                }
                if (aborted.get()) {
                    workerPool.shutdownNow();
                } else {
                    workerPool.shutdown();
                }
                try {
                    if (!workerPool.awaitTermination(30L, TimeUnit.SECONDS)) {
                        workerPool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    workerPool.shutdownNow();
                }
            }
            totalImportNanos = System.nanoTime() - createdNanos;
            long importedRows = importedCount.sum();
            double seconds = totalImportNanos / 1_000_000_000.0D;
            long rowsPerSecond = seconds > 0 ? (long) (importedRows / seconds) : 0L;
            // Final adaptive state: how far the AIMD gate grew/shrank and where the batch sizer
            // settled, for production observability and stress-test reporting.
            log.info("Import batcher finished: workers={}, batches={}, imported rows={} in {}s "
                            + "-> {} rows/s, final batch size={}, final gate permits={}",
                    workerCount, submittedBatches, importedRows, Math.round(seconds), rowsPerSecond,
                    batchSizer.batchSize(), gate == null ? 1 : gate.availablePermits());
            LAST_TUNING.set(new ImportTuningSnapshot(workerCount, submittedBatches, importedRows,
                    totalImportNanos, batchSizer.batchSize(),
                    gate == null ? 1 : gate.availablePermits(), peakInFlightBatches.get()));
            if (failure.get() == null) {
                // Tail checkpoint: after the final flush everything accepted is durable.
                try {
                    long rowsDone = durableWatermark() - 1;
                    context.checkpoint(ResumeState.builder()
                            .shardNo(0)
                            .kind(RESUME_KIND_IMPORT)
                            .cursorJson(resumeCursorJson(rowsDone))
                            .rowsDone(rowsDone)
                            .updatedAt(new Date())
                            .build());
                } catch (Throwable tailCheckpointFailure) {
                    log.warn("Final import resume checkpoint failed", tailCheckpointFailure);
                }
            }
            if (journal != null) {
                if (failure.get() == null) {
                    journal.cleanup();
                } else {
                    journal.progress("FAILED", durableWatermark() - 1);
                    journal.preserve();
                }
            }
            if (rejectWriter != null) {
                try {
                    rejectWriter.flush();
                    rejectWriter.close();
                } catch (IOException e) {
                    log.warn("Could not close import reject writer", e);
                }
            }
        }
    }
}
