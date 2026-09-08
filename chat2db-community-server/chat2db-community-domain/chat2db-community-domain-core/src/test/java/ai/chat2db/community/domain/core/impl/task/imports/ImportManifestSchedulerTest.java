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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        Thread run = new Thread(() -> new ImportManifestScheduler(memory.proxy()).execute(manifest, 2, null, shard -> {
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
    void recoversRunningShardsAndSkipsAlreadyCommittedShards() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = manifest();
        memory.save(state(0, ImportManifestScheduler.DONE, cursor(manifest, manifest.getShards().get(0)), 10L));
        memory.save(state(1, ImportManifestScheduler.RUNNING, cursor(manifest, manifest.getShards().get(1)), 0L));
        AtomicInteger executions = new AtomicInteger();

        new ImportManifestScheduler(memory.proxy()).execute(manifest, 2, null, shard -> {
            executions.incrementAndGet();
            return new ImportManifestScheduler.ShardResult(shard.getEstimatedRows(), 10L);
        });

        assertEquals(2, executions.get());
        assertEquals(ImportManifestScheduler.DONE, memory.states().get(1).getKind());
        assertEquals(ImportManifestScheduler.DONE, memory.states().get(2).getKind());
    }

    @Test
    void persistsFailureAndDoesNotOpenTheNextLayer() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = manifest();
        AtomicBoolean childStarted = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> new ImportManifestScheduler(memory.proxy())
                .execute(manifest, 2, null, shard -> {
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
    void rejectsResumeStateFromAnotherManifest() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = manifest();
        memory.save(state(0, ImportManifestScheduler.DONE,
                "{\"manifestFingerprint\":\"old\",\"shardId\":\"orders-1\",\"layer\":0}", 10L));

        assertThrows(IllegalStateException.class, () -> new ImportManifestScheduler(memory.proxy())
                .execute(manifest, 2, null, shard -> new ImportManifestScheduler.ShardResult(0, 0)));
    }

    @Test
    void retriesADeadlockedShardBeforePersistingDone() {
        MemoryResumeStorage memory = new MemoryResumeStorage();
        ImportManifest manifest = manifest();
        AtomicInteger attempts = new AtomicInteger();
        ImportShardRetryPolicy retry = new ImportShardRetryPolicy(1, 0L, 0L,
                ignored -> { }, ignored -> 0L);

        new ImportManifestScheduler(memory.proxy(), retry).execute(manifest, 2, null, shard -> {
            if ("orders-1".equals(shard.getShardId()) && attempts.incrementAndGet() == 1) {
                throw new SQLException("deadlock", "40001", 1213);
            }
            return new ImportManifestScheduler.ShardResult(shard.getEstimatedRows(), 10L);
        });

        assertEquals(2, attempts.get());
        assertEquals(ImportManifestScheduler.DONE, memory.states().get(0).getKind());
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
