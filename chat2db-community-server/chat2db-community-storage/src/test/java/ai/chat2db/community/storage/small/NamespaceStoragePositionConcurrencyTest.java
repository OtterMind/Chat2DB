package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.model.workspace.Namespace;
import ai.chat2db.community.storage.TestHome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespaceStoragePositionConcurrencyTest {

    private static final long TARGET_NAMESPACE_ID = 2002L;
    private static final long MOVED_DATA_SOURCE_ID = 9001L;
    private static final long DELETED_DATA_SOURCE_ID = 9002L;

    @BeforeAll
    static void useTempHome() {
        TestHome.init();
    }

    @Test
    void concurrentUpdateAndDeleteDataSourcePositionPersistsStableNamespaceState() throws Exception {
        NamespaceStorage storage = new NamespaceStorage();
        storage.save(namespace(1001L, List.of(MOVED_DATA_SOURCE_ID, DELETED_DATA_SOURCE_ID)));
        storage.save(namespace(TARGET_NAMESPACE_ID, List.of()));

        runConcurrently(List.of(
                () -> {
                    storage.updateDataSourcePosition(TARGET_NAMESPACE_ID, MOVED_DATA_SOURCE_ID);
                    return null;
                },
                () -> {
                    storage.deleteDataSourcePosition(DELETED_DATA_SOURCE_ID);
                    return null;
                }));

        NamespaceStorage reloaded = new NamespaceStorage();
        Map<Long, List<Long>> persistedPositions = Map.of(
                1001L, reloaded.getById(1001L).getDatasourceIds(),
                TARGET_NAMESPACE_ID, reloaded.getById(TARGET_NAMESPACE_ID).getDatasourceIds());

        assertEquals(List.of(), persistedPositions.get(1001L));
        assertEquals(List.of(MOVED_DATA_SOURCE_ID), persistedPositions.get(TARGET_NAMESPACE_ID));
    }

    private static Namespace namespace(Long id, List<Long> datasourceIds) {
        Namespace namespace = new Namespace();
        namespace.setId(id);
        namespace.setName("namespace-" + id);
        namespace.setDatasourceIds(new ArrayList<>(datasourceIds));
        return namespace;
    }

    private static void runConcurrently(List<Callable<Void>> operations) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(operations.size() * 8);
        try {
            List<Future<Void>> futures = IntStream.range(0, 100)
                    .mapToObj(index -> operations.get(index % operations.size()))
                    .map(operation -> executor.submit(() -> {
                        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        return operation.call();
                    }))
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
