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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Executes a frozen import manifest with persisted layer barriers and shard claims. */
public final class ImportManifestScheduler {

    static final String PENDING = "MANIFEST_PENDING";
    static final String RUNNING = "MANIFEST_RUNNING";
    static final String DONE = "MANIFEST_DONE";
    static final String FAILED = "MANIFEST_FAILED";

    private final TaskStorage storage;

    public ImportManifestScheduler(TaskStorage storage) {
        this.storage = storage;
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
        try {
            for (List<ImportManifestShard> layer : layers.values()) {
                check.run();
                ensureDependenciesCommitted(layer, completed);
                List<Future<String>> futures = new ArrayList<>();
                for (ImportManifestShard shard : layer) {
                    if (!completed.contains(shard.getShardId())) {
                        futures.add(workers.submit(() -> executeShard(manifest, shard, states, check, shardExecutor)));
                    }
                }
                for (Future<String> future : futures) {
                    try {
                        completed.add(future.get());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Manifest import was interrupted", interrupted);
                    } catch (ExecutionException failure) {
                        Throwable cause = failure.getCause();
                        if (cause instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        throw new IllegalStateException("Manifest shard execution failed", cause);
                    }
                }
            }
        } finally {
            workers.shutdownNow();
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
            ShardResult result = executor.execute(shard);
            ShardResult committed = result == null ? new ShardResult(0L, 0L) : result;
            if (!storage.compareAndSetResumeState(manifest.getTaskId(), state.index(), RUNNING,
                    resume(state.index(), DONE, cursor(manifest, shard), committed.rows(), committed.bytes()))) {
                throw new IllegalStateException("Committed shard state could not be persisted: " + shard.getShardId());
            }
            return shard.getShardId();
        } catch (Exception failure) {
            storage.compareAndSetResumeState(manifest.getTaskId(), state.index(), RUNNING,
                    resume(state.index(), FAILED, cursor(manifest, shard), 0L, 0L));
            throw failure;
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
                if (RUNNING.equals(state.getKind())) {
                    ResumeState pending = resume(index, PENDING, cursor, state.getRowsDone(), state.getBytesDone());
                    if (!storage.compareAndSetResumeState(manifest.getTaskId(), index, RUNNING, pending)) {
                        throw new IllegalStateException("Stale running shard could not be recovered: "
                                + shard.getShardId());
                    }
                    state = pending;
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
        /** Returns only after the shard transaction has committed and is visible to later layers. */
        ShardResult execute(ImportManifestShard shard) throws Exception;
    }

    public record ShardResult(long rows, long bytes) {
    }

    private record ShardState(int index, String kind) {
    }

}
