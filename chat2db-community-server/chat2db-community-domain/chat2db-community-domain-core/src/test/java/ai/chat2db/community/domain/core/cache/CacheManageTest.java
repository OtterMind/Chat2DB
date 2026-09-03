package ai.chat2db.community.domain.core.cache;

import org.junit.jupiter.api.Test;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void corruptScalarEntryIsEvictedBeforeFallbackAndRepaired() {
        CacheManager cacheManager = newCacheManager();
        CacheManage.CacheStore cacheStore = new CacheManage.CacheStore(() -> cacheManager);
        Cache<String, String> cache = cacheManager.getCache("meta_cache", String.class, String.class);
        cache.put("scalar", "{");
        AtomicInteger fallbackCalls = new AtomicInteger();

        try {
            String first = cacheStore.get("scalar", String.class, ignored -> false, ignored -> {
                assertNull(cache.get("scalar"));
                fallbackCalls.incrementAndGet();
                return "fallback";
            });
            String second = cacheStore.get("scalar", String.class, ignored -> false, ignored -> {
                fallbackCalls.incrementAndGet();
                return "unexpected";
            });

            assertEquals("fallback", first);
            assertEquals("fallback", second);
            assertEquals(1, fallbackCalls.get());
        } finally {
            cacheStore.close();
        }
    }

    @Test
    void corruptListEntryFallsBackAndIsRepaired() {
        CacheManager cacheManager = newCacheManager();
        CacheManage.CacheStore cacheStore = new CacheManage.CacheStore(() -> cacheManager);
        Cache<String, String> cache = cacheManager.getCache("meta_cache", String.class, String.class);
        cache.put("list", "{");
        AtomicInteger fallbackCalls = new AtomicInteger();

        try {
            List<String> first = cacheStore.getList("list", String.class, ignored -> false, ignored -> {
                fallbackCalls.incrementAndGet();
                return List.of("fallback");
            });
            List<String> second = cacheStore.getList("list", String.class, ignored -> false, ignored -> {
                fallbackCalls.incrementAndGet();
                return List.of("unexpected");
            });

            assertEquals(List.of("fallback"), first);
            assertEquals(List.of("fallback"), second);
            assertEquals(1, fallbackCalls.get());
        } finally {
            cacheStore.close();
        }
    }

    private static CacheManager newCacheManager() {
        return CacheManagerBuilder.newCacheManagerBuilder()
                .withCache("meta_cache", CacheConfigurationBuilder.newCacheConfigurationBuilder(
                        String.class, String.class,
                        ResourcePoolsBuilder.newResourcePoolsBuilder().heap(10, EntryUnit.ENTRIES)))
                .build(true);
    }
}
