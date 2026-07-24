package ai.chat2db.community.domain.core.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryCacheManageTest {

    @Test
    void distinctKeysUseABoundedStripeSet() {
        List<String> keys = new ArrayList<>();
        try {
            for (int i = 0; i < 2048; i++) {
                String key = "distinct-key-" + UUID.randomUUID();
                String value = "value-" + i;
                keys.add(key);
                assertEquals(value, MemoryCacheManage.computeIfAbsent(key, () -> value));
            }
            assertEquals(1024, MemoryCacheManage.LOCKS.size());
        } finally {
            keys.forEach(MemoryCacheManage::remove);
        }
    }

    @Test
    void sameKeyIsLoadedOnlyOnceUnderConcurrency() throws Exception {
        String key = "same-key-" + UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return MemoryCacheManage.computeIfAbsent(key, () -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return "value";
                    });
                }));
            }

            start.countDown();
            for (Future<String> future : futures) {
                assertEquals("value", future.get());
            }
            assertEquals(1, loads.get());
            assertEquals(1024, MemoryCacheManage.LOCKS.size());
        } finally {
            executor.shutdownNow();
            MemoryCacheManage.remove(key);
        }
    }
}
