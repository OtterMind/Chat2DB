package ai.chat2db.community.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IdUtilTest {

    @Test
    void backToBackCallsMustNotProduceDuplicateIds() {
        int count = 5000;
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            ids.add(IdUtil.generateId());
        }
        assertEquals(count, ids.size(), "ids generated in a tight loop must be unique");
    }

    @Test
    void idsMustBeStrictlyIncreasing() {
        Long previous = IdUtil.generateId();
        for (int i = 0; i < 1000; i++) {
            Long next = IdUtil.generateId();
            assertTrue(next > previous, "ids must be strictly increasing: " + previous + " -> " + next);
            previous = next;
        }
    }

    @Test
    void concurrentCallsMustNotProduceDuplicateIds() throws Exception {
        int threads = 8;
        int perThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    List<Long> ids = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        ids.add(IdUtil.generateId());
                    }
                    return ids;
                }));
            }
            Set<Long> all = new HashSet<>();
            for (Future<List<Long>> future : futures) {
                all.addAll(future.get());
            }
            assertEquals(threads * perThread, all.size(), "ids generated concurrently must be unique");
        } finally {
            executor.shutdownNow();
        }
    }
}
