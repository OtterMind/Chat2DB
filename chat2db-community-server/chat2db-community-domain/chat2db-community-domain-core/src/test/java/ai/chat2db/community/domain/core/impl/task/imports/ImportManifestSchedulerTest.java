package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportManifestSchedulerTest {

    @Test
    void runsEachLayerConcurrentlyAndWaitsForEveryCommittedParent() throws Exception {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = manifest();
        CountDownLatch parentsStarted = new CountDownLatch(2);
        CountDownLatch releaseParents = new CountDownLatch(1);
        AtomicBoolean childStartedBeforeParentsCommitted = new AtomicBoolean();
        AtomicInteger committedParents = new AtomicInteger();

        Thread run = new Thread(() -> new ImportManifestScheduler(memory.proxy()).execute(manifest, 2, null,
                (shard, cancellationCheck) -> {
            if (shard.getLayer() == 0) {
                parentsStarted.countDown();
                assertTrue(releaseParents.await(5, TimeUnit.SECONDS));
                committedParents.incrementAndGet();
            } else if (committedParents.get() != 2) {
                childStartedBeforeParentsCommitted.set(true);
            }
            return new ImportManifestScheduler.ShardResult(shard.getEstimatedRows(), 64L);
        }));
        run.start();
        assertTrue(parentsStarted.await(5, TimeUnit.SECONDS));
        releaseParents.countDown();
        run.join(5000);

        assertTrue(!run.isAlive());
        assertTrue(!childStartedBeforeParentsCommitted.get());
        assertEquals(List.of(ImportManifestScheduler.DONE, ImportManifestScheduler.DONE,
                        ImportManifestScheduler.DONE),
                memory.states().stream().map(ResumeState::getKind).toList());
        assertEquals(List.of(10L, 20L, 30L),
                memory.states().stream().map(ResumeState::getRowsDone).toList());
    }

    @Test
    void refusesToReplayAShardWithAnUnknownRunningOutcome() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = manifest();
        memory.save(state(0, ImportManifestScheduler.DONE, cursor(manifest, manifest.getShards().get(0)), 10L));
        memory.save(state(1, ImportManifestScheduler.RUNNING, cursor(manifest, manifest.getShards().get(1)), 0L));
        AtomicInteger executions = new AtomicInteger();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ImportManifestScheduler(memory.proxy()).execute(manifest, 2, null,
                        (shard, cancellationCheck) -> {
                            executions.incrementAndGet();
                            return new ImportManifestScheduler.ShardResult(shard.getEstimatedRows(), 10L);
                        }));

        assertTrue(failure.getMessage().contains("unknown commit outcome"));
        assertEquals(0, executions.get());
        assertEquals(ImportManifestScheduler.RUNNING, memory.states().get(1).getKind());
    }

    @Test
    void persistsFailureAndDoesNotOpenTheNextLayer() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = manifest();
        AtomicBoolean childStarted = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> new ImportManifestScheduler(memory.proxy())
                .execute(manifest, 2, null, (shard, cancellationCheck) -> {
                    if (shard.getLayer() == 1) {
                        childStarted.set(true);
                    }
                    if ("customers-1".equals(shard.getShardId())) {
                        throw new IllegalStateException("controlled failure");
                    }
                    return new ImportManifestScheduler.ShardResult(shard.getEstimatedRows(), 10L);
                }));

        assertTrue(!childStarted.get());
        assertEquals(ImportManifestScheduler.FAILED, memory.states().get(1).getKind());
    }

    @Test
    void cancelsQueuedShardsAndWaitsForBlockingWorkerCleanupBeforeFailureReturns() throws Exception {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = failingLayerManifest();
        CountDownLatch activeWorkersStarted = new CountDownLatch(2);
        CountDownLatch blockForever = new CountDownLatch(1);
        CountDownLatch blockingWorkerCleanupStarted = new CountDownLatch(1);
        CountDownLatch allowBlockingWorkerCleanup = new CountDownLatch(1);
        CountDownLatch schedulerFinished = new CountDownLatch(1);
        AtomicBoolean blockingWorkerCleanupFinished = new AtomicBoolean();
        AtomicBoolean queuedShardStarted = new AtomicBoolean();
        AtomicReference<Throwable> schedulerFailure = new AtomicReference<>();

        Thread run = new Thread(() -> {
            try {
                new ImportManifestScheduler(memory.proxy()).execute(manifest, 2, null,
                        (shard, cancellationCheck) -> {
                    if ("blocking-1".equals(shard.getShardId())) {
                        activeWorkersStarted.countDown();
                        assertTrue(activeWorkersStarted.await(5, TimeUnit.SECONDS));
                        try {
                            try {
                                blockForever.await();
                            } catch (InterruptedException ignored) {
                                Thread.interrupted();
                            }
                            cancellationCheck.run();
                            return new ImportManifestScheduler.ShardResult(1L, 1L);
                        } finally {
                            blockingWorkerCleanupStarted.countDown();
                            try {
                                assertTrue(awaitUninterruptibly(allowBlockingWorkerCleanup, 5, TimeUnit.SECONDS));
                            } finally {
                                blockingWorkerCleanupFinished.set(true);
                            }
                        }
                    }
                    if ("failing-1".equals(shard.getShardId())) {
                        activeWorkersStarted.countDown();
                        assertTrue(activeWorkersStarted.await(5, TimeUnit.SECONDS));
                        throw new IllegalStateException("controlled fast failure");
                    }
                    queuedShardStarted.set(true);
                    return new ImportManifestScheduler.ShardResult(1L, 1L);
                });
            } catch (Throwable failure) {
                schedulerFailure.set(failure);
            } finally {
                schedulerFinished.countDown();
            }
        }, "manifest-scheduler-regression");
        run.setDaemon(true);
        run.start();

        try {
            assertTrue(blockingWorkerCleanupStarted.await(5, TimeUnit.SECONDS));
            assertFalse(schedulerFinished.await(200, TimeUnit.MILLISECONDS));
            assertFalse(blockingWorkerCleanupFinished.get());
            assertFalse(queuedShardStarted.get());
        } finally {
            blockForever.countDown();
            allowBlockingWorkerCleanup.countDown();
        }
        assertTrue(schedulerFinished.await(5, TimeUnit.SECONDS));
        run.join(5000);
        assertFalse(run.isAlive());
        assertTrue(blockingWorkerCleanupFinished.get());
        assertFalse(queuedShardStarted.get());
        assertTrue(schedulerFailure.get() instanceof IllegalStateException);
        assertEquals("controlled fast failure", schedulerFailure.get().getMessage());
        assertEquals(List.of(ImportManifestScheduler.FAILED, ImportManifestScheduler.FAILED,
                        ImportManifestScheduler.PENDING),
                memory.states().stream().map(ResumeState::getKind).toList());
    }

    private boolean awaitUninterruptibly(CountDownLatch latch, long timeout, TimeUnit unit) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return latch.getCount() == 0L;
                }
                try {
                    return latch.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void rejectsResumeStateFromAnotherManifest() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = manifest();
        memory.save(state(0, ImportManifestScheduler.DONE,
                "{\"manifestFingerprint\":\"old\",\"shardId\":\"orders-1\",\"layer\":0}", 10L));

        assertThrows(IllegalStateException.class, () -> new ImportManifestScheduler(memory.proxy())
                .execute(manifest, 2, null,
                        (shard, cancellationCheck) -> new ImportManifestScheduler.ShardResult(0, 0)));
    }

    @Test
    void retriesADeadlockedShardBeforePersistingDone() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = manifest();
        AtomicInteger attempts = new AtomicInteger();
        ImportShardRetryPolicy retry = new ImportShardRetryPolicy(1, 0L, 0L,
                ignored -> { }, ignored -> 0L);

        new ImportManifestScheduler(memory.proxy(), retry).execute(manifest, 2, null,
                (shard, cancellationCheck) -> {
            if ("orders-1".equals(shard.getShardId()) && attempts.incrementAndGet() == 1) {
                throw new SQLException("deadlock", "40001", 1213);
            }
            return new ImportManifestScheduler.ShardResult(shard.getEstimatedRows(), 10L);
        });

        assertEquals(2, attempts.get());
        assertEquals(ImportManifestScheduler.DONE, memory.states().get(0).getKind());
    }

    @Test
    void marksCommittedShardUnknownWhenDoneCheckpointCannotBePersisted() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = singleShardManifest();
        memory.failDoneTransition(0);
        AtomicInteger executions = new AtomicInteger();

        ImportManifestScheduler.CommitOutcomeUnknownException failure = assertThrows(
                ImportManifestScheduler.CommitOutcomeUnknownException.class,
                () -> new ImportManifestScheduler(memory.proxy()).execute(manifest, 1, null,
                        (shard, cancellationCheck) -> {
                            executions.incrementAndGet();
                            return new ImportManifestScheduler.ShardResult(1L, 10L);
                        }));

        assertTrue(failure.getMessage().contains("durable completion state"));
        assertEquals(1, executions.get());
        assertEquals(ImportManifestScheduler.COMMIT_UNKNOWN, memory.states().get(0).getKind());
    }

    @Test
    void doesNotDeadlockRetryAnExplicitUnknownCommitOutcome() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = singleShardManifest();
        AtomicInteger attempts = new AtomicInteger();
        ImportShardRetryPolicy retry = new ImportShardRetryPolicy(2, 0L, 0L,
                ignored -> { }, ignored -> 0L);

        assertThrows(ImportManifestScheduler.CommitOutcomeUnknownException.class,
                () -> new ImportManifestScheduler(memory.proxy(), retry).execute(manifest, 1, null,
                        (shard, cancellationCheck) -> {
                            attempts.incrementAndGet();
                            throw new ImportManifestScheduler.CommitOutcomeUnknownException(
                                    "commit unknown", new SQLException("deadlock", "40P01"));
                        }));

        assertEquals(1, attempts.get());
        assertEquals(ImportManifestScheduler.COMMIT_UNKNOWN, memory.states().get(0).getKind());
    }

    private ImportManifest manifest() {
        ImportManifestShard orders = shard("orders-1", "orders", 0, 10, List.of());
        ImportManifestShard customers = shard("customers-1", "customers", 0, 20, List.of());
        ImportManifestShard items = shard("items-1", "items", 1, 30,
                List.of("orders-1", "customers-1"));
        return ImportManifest.builder().schemaVersion(1).taskId(42L).mode(ImportPlanMode.PARALLEL_LAYER)
                .sourceFingerprint("source").manifestFingerprint("manifest-abc")
                .shards(List.of(orders, customers, items)).build();
    }

    private ImportManifest failingLayerManifest() {
        ImportManifestShard blocking = shard("blocking-1", "blocking", 0, 1, List.of());
        ImportManifestShard failing = shard("failing-1", "failing", 0, 1, List.of());
        ImportManifestShard queued = shard("queued-1", "queued", 0, 1, List.of());
        return ImportManifest.builder().schemaVersion(1).taskId(43L).mode(ImportPlanMode.PARALLEL_LAYER)
                .sourceFingerprint("source").manifestFingerprint("manifest-failure")
                .shards(List.of(blocking, failing, queued)).build();
    }

    private ImportManifest singleShardManifest() {
        return ImportManifest.builder().schemaVersion(1).taskId(44L).mode(ImportPlanMode.SERIAL_SAFE)
                .sourceFingerprint("source").manifestFingerprint("manifest-single")
                .shards(List.of(shard("single-1", "single", 0, 1, List.of()))).build();
    }

    private ImportManifestShard shard(String id, String table, int layer, long rows, List<String> dependencies) {
        return ImportManifestShard.builder().shardId(id).tableName(table).layer(layer)
                .sourcePath(id + ".csv").estimatedRows(rows).dependencyShardIds(dependencies).build();
    }

    private String cursor(ImportManifest manifest, ImportManifestShard shard) {
        return "{\"manifestFingerprint\":\"" + manifest.getManifestFingerprint()
                + "\",\"shardId\":\"" + shard.getShardId() + "\",\"layer\":" + shard.getLayer() + "}";
    }

    private ResumeState state(int index, String kind, String cursor, long rows) {
        return ResumeState.builder().shardNo(index).kind(kind).cursorJson(cursor)
                .rowsDone(rows).bytesDone(0L).updatedAt(new Date()).build();
    }

    private static final class MemoryResumeStorage {
        private final Map<Integer, ResumeState> states = new HashMap<>();
        private Integer failDoneIndex;

        private synchronized void failDoneTransition(int index) {
            failDoneIndex = index;
        }

        private synchronized void save(ResumeState state) {
            states.put(state.getShardNo(), copy(state));
        }

        private synchronized List<ResumeState> states() {
            return states.values().stream().sorted(java.util.Comparator.comparing(ResumeState::getShardNo))
                    .map(MemoryResumeStorage::copy).toList();
        }

        private TaskStorage proxy() {
            return (TaskStorage) Proxy.newProxyInstance(TaskStorage.class.getClassLoader(),
                    new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "saveResumeState" -> {
                                save((ResumeState) args[1]);
                                yield null;
                            }
                            case "listResumeStates" -> states();
                            case "compareAndSetResumeState" -> compareAndSet((Integer) args[1],
                                    (String) args[2], (ResumeState) args[3]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private synchronized boolean compareAndSet(Integer index, String expected, ResumeState target) {
            ResumeState current = states.get(index);
            if (current == null || !expected.equals(current.getKind())) {
                return false;
            }
            if (index.equals(failDoneIndex) && ImportManifestScheduler.DONE.equals(target.getKind())) {
                failDoneIndex = null;
                return false;
            }
            states.put(index, copy(target));
            return true;
        }

        private static ResumeState copy(ResumeState source) {
            return ResumeState.builder().shardNo(source.getShardNo()).kind(source.getKind())
                    .cursorJson(source.getCursorJson()).rowsDone(source.getRowsDone())
                    .bytesDone(source.getBytesDone()).updatedAt(source.getUpdatedAt()).build();
        }
    }
}
