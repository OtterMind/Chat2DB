package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Executes a frozen import manifest with persisted layer barriers and shard claims. */
public final class ImportManifestScheduler {

    private static final long WORKER_TERMINATION_TIMEOUT_SECONDS = 30L;

    static final String PENDING = "MANIFEST_PENDING";
    static final String RUNNING = "MANIFEST_RUNNING";
    static final String DONE = "MANIFEST_DONE";
    static final String FAILED = "MANIFEST_FAILED";
    static final String COMMIT_UNKNOWN = "MANIFEST_COMMIT_UNKNOWN";

    private final TaskStorage storage;
    private final ImportShardRetryPolicy retryPolicy;

    public ImportManifestScheduler(TaskStorage storage) {
        this(storage, new ImportShardRetryPolicy());
    }

    ImportManifestScheduler(TaskStorage storage, ImportShardRetryPolicy retryPolicy) {
        this.storage = storage;
        this.retryPolicy = retryPolicy;
    }

    public void execute(ImportManifest manifest, int maxConcurrency, Runnable cancellationCheck,
            ShardExecutor shardExecutor) {
        requireManifest(manifest, maxConcurrency, shardExecutor);
        Runnable check = cancellationCheck == null ? () -> { } : cancellationCheck;
        Map<String, ShardState> states = initialize(manifest);
        Map<Integer, List<ImportManifestShard>> layers = new TreeMap<>();
        for (ImportManifestShard shard : manifest.getShards()) {
            layers.computeIfAbsent(shard.getLayer(), ignored -> new ArrayList<>()).add(shard);
        }
        Set<String> completed = new HashSet<>();
        states.forEach((shardId, state) -> {
            if (DONE.equals(state.kind())) {
                completed.add(shardId);
            }
        });

        ExecutorService workers = Executors.newFixedThreadPool(maxConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "chat2db-manifest-import");
            thread.setDaemon(true);
            return thread;
        });
        Throwable executionFailure = null;
        try {
            for (List<ImportManifestShard> layer : layers.values()) {
                check.run();
                ensureDependenciesCommitted(layer, completed);
                executeLayer(manifest, layer, completed, states, check, shardExecutor, workers);
            }
        } catch (RuntimeException | Error failure) {
            executionFailure = failure;
            throw failure;
        } finally {
            shutdownAndAwait(workers, executionFailure);
        }
    }

    private void executeLayer(ImportManifest manifest, List<ImportManifestShard> layer,
            Set<String> completed, Map<String, ShardState> states, Runnable cancellationCheck,
            ShardExecutor shardExecutor, ExecutorService workers) {
        AtomicBoolean layerCancelled = new AtomicBoolean();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Runnable sharedCancellationCheck = () -> {
            if (layerCancelled.get()) {
                throw new CancellationException("Manifest layer was cancelled after a shard failure");
            }
            cancellationCheck.run();
        };
        CompletionService<String> completions = new ExecutorCompletionService<>(workers);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (ImportManifestShard shard : layer) {
                if (!completed.contains(shard.getShardId())) {
                    futures.add(completions.submit(() -> executeShardAndSignalFailure(manifest, shard, states,
                            sharedCancellationCheck, shardExecutor, layerCancelled, firstFailure)));
                }
            }
            for (int index = 0; index < futures.size(); index++) {
                try {
                    completed.add(completions.take().get());
                } catch (InterruptedException interrupted) {
                    cancelLayer(layerCancelled, futures);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Manifest import was interrupted", interrupted);
                } catch (ExecutionException failure) {
                    cancelLayer(layerCancelled, futures);
                    Throwable cause = firstFailure.get();
                    throw propagateWorkerFailure(cause == null ? failure.getCause() : cause);
                }
            }
        } catch (RuntimeException | Error failure) {
            cancelLayer(layerCancelled, futures);
            throw failure;
        }
    }

    private String executeShardAndSignalFailure(ImportManifest manifest, ImportManifestShard shard,
            Map<String, ShardState> states, Runnable cancellationCheck, ShardExecutor executor,
            AtomicBoolean layerCancelled, AtomicReference<Throwable> firstFailure) throws Exception {
        try {
            return executeShard(manifest, shard, states, cancellationCheck, executor);
        } catch (Exception failure) {
            firstFailure.compareAndSet(null, failure);
            layerCancelled.set(true);
            throw failure;
        } catch (Error failure) {
            firstFailure.compareAndSet(null, failure);
            layerCancelled.set(true);
            throw failure;
        }
    }

    private static void cancelLayer(AtomicBoolean layerCancelled, List<? extends Future<?>> futures) {
        layerCancelled.set(true);
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static RuntimeException propagateWorkerFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Manifest shard execution failed", cause);
    }

    private static void shutdownAndAwait(ExecutorService workers, Throwable executionFailure) {
        if (executionFailure == null) {
            workers.shutdown();
        } else {
            workers.shutdownNow();
        }
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(WORKER_TERMINATION_TIMEOUT_SECONDS);
        boolean terminated = workers.isTerminated();
        try {
            while (!terminated) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    terminated = workers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException cleanupInterrupted) {
                    interrupted = true;
                    workers.shutdownNow();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (!terminated) {
            IllegalStateException timeout = new IllegalStateException(
                    "Manifest import workers did not terminate within "
                            + WORKER_TERMINATION_TIMEOUT_SECONDS + " seconds");
            if (executionFailure != null) {
                executionFailure.addSuppressed(timeout);
            } else {
                throw timeout;
            }
        }
    }

    private String executeShard(ImportManifest manifest, ImportManifestShard shard,
            Map<String, ShardState> states, Runnable cancellationCheck, ShardExecutor executor) throws Exception {
        cancellationCheck.run();
        ShardState state = states.get(shard.getShardId());
        if (!PENDING.equals(state.kind()) || !storage.compareAndSetResumeState(manifest.getTaskId(), state.index(),
                PENDING, resume(state.index(), RUNNING, cursor(manifest, shard), 0L, 0L))) {
            throw new IllegalStateException("Manifest shard could not be claimed: " + shard.getShardId());
        }
        try {
            ShardResult result = retryPolicy.execute(() -> executor.execute(shard, cancellationCheck));
            ShardResult committed = result == null ? new ShardResult(0L, 0L) : result;
            if (!storage.compareAndSetResumeState(manifest.getTaskId(), state.index(), RUNNING,
                    resume(state.index(), DONE, cursor(manifest, shard), committed.rows(), committed.bytes()))) {
                CommitOutcomeUnknownException unknown = new CommitOutcomeUnknownException(
                        "Shard committed but its durable completion state could not be persisted: "
                                + shard.getShardId());
                storage.compareAndSetResumeState(manifest.getTaskId(), state.index(), RUNNING,
                        resume(state.index(), COMMIT_UNKNOWN, cursor(manifest, shard),
                                committed.rows(), committed.bytes()));
                throw unknown;
            }
            return shard.getShardId();
        } catch (Throwable failure) {
            String failureKind = isCommitOutcomeUnknown(failure) ? COMMIT_UNKNOWN : FAILED;
            storage.compareAndSetResumeState(manifest.getTaskId(), state.index(), RUNNING,
                    resume(state.index(), failureKind, cursor(manifest, shard), 0L, 0L));
            if (failure instanceof Exception exception) {
                throw exception;
            }
            throw (Error) failure;
        }
    }

    private Map<String, ShardState> initialize(ImportManifest manifest) {
        Map<Integer, ResumeState> persisted = new HashMap<>();
        for (ResumeState state : storage.listResumeStates(manifest.getTaskId())) {
            if (state.getKind() != null && state.getKind().startsWith("MANIFEST_")) {
                persisted.put(state.getShardNo(), state);
            }
        }
        Map<String, ShardState> result = new HashMap<>();
        for (int index = 0; index < manifest.getShards().size(); index++) {
            ImportManifestShard shard = manifest.getShards().get(index);
            ResumeState state = persisted.get(index);
            String cursor = cursor(manifest, shard);
            if (state == null) {
                state = resume(index, PENDING, cursor, 0L, 0L);
                storage.saveResumeState(manifest.getTaskId(), state);
            } else {
                validateCursor(cursor, state, shard.getShardId());
                if (RUNNING.equals(state.getKind()) || COMMIT_UNKNOWN.equals(state.getKind())) {
                    throw new IllegalStateException("Manifest shard has an unknown commit outcome and requires "
                            + "operator reconciliation before retry: " + shard.getShardId());
                } else if (FAILED.equals(state.getKind())) {
                    throw new IllegalStateException("Manifest contains a failed shard requiring retry approval: "
                            + shard.getShardId());
                } else if (!PENDING.equals(state.getKind()) && !DONE.equals(state.getKind())) {
                    throw new IllegalStateException("Unknown manifest shard state: " + state.getKind());
                }
            }
            result.put(shard.getShardId(), new ShardState(index, state.getKind()));
        }
        return result;
    }

    private void ensureDependenciesCommitted(List<ImportManifestShard> layer, Set<String> completed) {
        for (ImportManifestShard shard : layer) {
            for (String dependency : shard.getDependencyShardIds()) {
                if (!completed.contains(dependency)) {
                    throw new IllegalStateException("Manifest dependency is not committed: "
                            + shard.getShardId() + " -> " + dependency);
                }
            }
        }
    }

    private void requireManifest(ImportManifest manifest, int maxConcurrency, ShardExecutor executor) {
        if (manifest == null || manifest.getTaskId() == null || manifest.getManifestFingerprint() == null
                || manifest.getShards() == null || manifest.getShards().isEmpty()) {
            throw new IllegalArgumentException("A persisted manifest with shards is required");
        }
        if (maxConcurrency <= 0 || executor == null) {
            throw new IllegalArgumentException("Manifest concurrency and shard executor are required");
        }
    }

    private String cursor(ImportManifest manifest, ImportManifestShard shard) {
        return "{\"manifestFingerprint\":" + JSON.toJSONString(manifest.getManifestFingerprint())
                + ",\"shardId\":" + JSON.toJSONString(shard.getShardId())
                + ",\"layer\":" + shard.getLayer() + "}";
    }

    private void validateCursor(String expected, ResumeState state, String shardId) {
        if (!expected.equals(state.getCursorJson())) {
            throw new IllegalStateException("Persisted shard state does not match the manifest: " + shardId);
        }
    }

    private ResumeState resume(int index, String kind, String cursor, Long rows, Long bytes) {
        return ResumeState.builder().shardNo(index).kind(kind).cursorJson(cursor)
                .rowsDone(rows).bytesDone(bytes).updatedAt(new Date()).build();
    }

    @FunctionalInterface
    public interface ShardExecutor {
        /** Checks cancellation during long work and immediately before committing the shard transaction. */
        ShardResult execute(ImportManifestShard shard, Runnable cancellationCheck) throws Exception;
    }

    public record ShardResult(long rows, long bytes) {
    }

    static boolean isCommitOutcomeUnknown(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CommitOutcomeUnknownException) {
                return true;
            }
        }
        return false;
    }

    static final class CommitOutcomeUnknownException extends IllegalStateException {
        CommitOutcomeUnknownException(String message) {
            super(message);
        }

        CommitOutcomeUnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record ShardState(int index, String kind) {
    }

}
