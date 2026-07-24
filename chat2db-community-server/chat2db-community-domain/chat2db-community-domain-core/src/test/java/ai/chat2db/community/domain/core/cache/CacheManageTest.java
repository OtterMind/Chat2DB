package ai.chat2db.community.domain.core.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheManageTest {

    @Test
    void initializationExceptionDisablesCache() {
        CacheManage.CacheStore cacheStore = new CacheManage.CacheStore(() -> {
            throw new IllegalStateException("cache unavailable");
        });

        assertFalse(cacheStore.isAvailable());
    }

    @Test
    void initializationLinkageErrorDisablesCache() {
        CacheManage.CacheStore cacheStore = new CacheManage.CacheStore(() -> {
            throw new LinkageError("cache dependency unavailable");
        });

        assertFalse(cacheStore.isAvailable());
    }

    @Test
    void fatalErrorsEscapeInitialization() {
        assertThrows(OutOfMemoryError.class, () -> new CacheManage.CacheStore(() -> {
            throw new OutOfMemoryError("fatal");
        }));
        assertThrows(StackOverflowError.class, () -> new CacheManage.CacheStore(() -> {
            throw new StackOverflowError("fatal");
        }));
        assertThrows(ThreadDeath.class, () -> new CacheManage.CacheStore(() -> {
            throw new ThreadDeath();
        }));
    }

    @Test
    void disabledCacheUsesFallbackAndLifecycleOperationsAreNoOps() {
        CacheManage.CacheStore cacheStore = new CacheManage.CacheStore(() -> {
            throw new IllegalStateException("cache unavailable");
        });
        AtomicInteger fallbackCalls = new AtomicInteger();

        String value = cacheStore.get("key", String.class,
                ignored -> {
                    throw new AssertionError("refresh must not run when cache is disabled");
                },
                ignored -> {
                    fallbackCalls.incrementAndGet();
                    return "fallback";
                });
        List<String> values = cacheStore.getList("list", String.class,
                ignored -> {
                    throw new AssertionError("refresh must not run when cache is disabled");
                },
                ignored -> {
                    fallbackCalls.incrementAndGet();
                    return List.of("fallback");
                });

        assertEquals("fallback", value);
        assertEquals(List.of("fallback"), values);
        assertEquals(2, fallbackCalls.get());
        assertDoesNotThrow(() -> cacheStore.fuzzyDelete("key"));
        assertDoesNotThrow(cacheStore::close);
        assertDoesNotThrow(cacheStore::close);
    }
}
