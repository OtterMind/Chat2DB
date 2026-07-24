package ai.chat2db.community.domain.core.cache;

import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

import ai.chat2db.community.tools.util.ConfigUtils;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;

import lombok.extern.slf4j.Slf4j;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class CacheManage {
    private static final String PATH = ConfigUtils.getBasePath()
            + File.separator
            + "cache" + File.separator + "chat2db-community-ehcache-data_" + StringUtils.defaultString(System.getProperty("spring.profiles.active"), "dev");

    private static final String CACHE = "meta_cache";

    private static final CacheStore CACHE_STORE = new CacheStore(CacheManage::createCacheManager);

    private static CacheManager createCacheManager() {
        return CacheManagerBuilder.newCacheManagerBuilder()
                .with(CacheManagerBuilder.persistence(PATH))
                .withCache(CACHE, CacheConfigurationBuilder.newCacheConfigurationBuilder(String.class, String.class,
                        ResourcePoolsBuilder.newResourcePoolsBuilder()
                                .heap(10000, EntryUnit.ENTRIES)
                                .disk(2, MemoryUnit.GB, true)).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(4))))
                .build(true);
    }

    public static <T> T get(String key, Class<T> clazz, Function<Object, Boolean> refresh,
                            Function<Object, T> function) {
        return CACHE_STORE.get(key, clazz, refresh, function);
    }

    public static <T> List<T> getList(String key, Class<T> clazz, Function<Object, Boolean> refresh,
                                      Function<Object, List<T>> function) {
        return CACHE_STORE.getList(key, clazz, refresh, function);
    }

    public static void fuzzyDelete(String key) {
        CACHE_STORE.fuzzyDelete(key);
    }


    public static void close() {
        CACHE_STORE.close();
    }

    @FunctionalInterface
    interface CacheManagerFactory {
        CacheManager create();
    }

    static final class CacheStore {
        private final CacheManagerFactory cacheManagerFactory;
        private volatile CacheState state;

        CacheStore(CacheManagerFactory cacheManagerFactory) {
            this.cacheManagerFactory = cacheManagerFactory;
            this.state = initialize();
        }

        boolean isAvailable() {
            return state instanceof AvailableCache;
        }

        <T> T get(String key, Class<T> clazz, Function<Object, Boolean> refresh,
                  Function<Object, T> function) {
            Optional<Cache<String, String>> cache = currentCache();
            if (cache.isEmpty()) {
                return function.apply(key);
            }
            Cache<String, String> currentCache = cache.get();
            T value;
            if (refresh.apply(key)) {
                remove(currentCache, key);
                value = function.apply(key);
                put(currentCache, key, value);
            } else {
                value = get(currentCache, key, clazz);
                if (value == null) {
                    value = function.apply(key);
                    put(currentCache, key, value);
                }
            }
            return value;
        }

        <T> List<T> getList(String key, Class<T> clazz, Function<Object, Boolean> refresh,
                            Function<Object, List<T>> function) {
            Optional<Cache<String, String>> cache = currentCache();
            if (cache.isEmpty()) {
                return function.apply(key);
            }
            Cache<String, String> currentCache = cache.get();
            List<T> value;
            if (refresh.apply(key)) {
                remove(currentCache, key);
                value = function.apply(key);
                put(currentCache, key, value);
            } else {
                value = getList(currentCache, key, clazz);
                if (value == null) {
                    value = function.apply(key);
                    put(currentCache, key, value);
                }
            }
            return value;
        }

        void fuzzyDelete(String key) {
            Optional<Cache<String, String>> cache = currentCache();
            if (cache.isEmpty()) {
                return;
            }
            Cache<String, String> currentCache = cache.get();
            try {
                Set<String> removes = new HashSet<>();
                currentCache.forEach(entry -> {
                    if (entry.getKey() != null && entry.getKey().startsWith(key) && !entry.getKey().equals(key)) {
                        removes.add(entry.getKey());
                    }
                });
                currentCache.removeAll(removes);
            } catch (Exception e) {
                FileUtil.del(PATH);
                state = initialize();
            }
        }

        void close() {
            log.info("close cache");
            CacheState currentState = state;
            state = UnavailableCache.INSTANCE;
            if (currentState instanceof AvailableCache availableCache) {
                try {
                    availableCache.cacheManager().close();
                } catch (Exception e) {
                    log.error("", e);
                }
            }
        }

        private CacheState initialize() {
            try {
                return new AvailableCache(Objects.requireNonNull(cacheManagerFactory.create()));
            } catch (Exception | LinkageError e) {
                log.error("init error", e);
                return UnavailableCache.INSTANCE;
            }
        }

        private Optional<Cache<String, String>> currentCache() {
            CacheState currentState = state;
            if (currentState instanceof AvailableCache availableCache) {
                return Optional.ofNullable(availableCache.cacheManager()
                        .getCache(CACHE, String.class, String.class));
            }
            return Optional.empty();
        }

        private <T> T get(Cache<String, String> cache, String key, Class<T> clazz) {
            String value = cache.get(key);
            if (!StringUtils.isEmpty(value)) {
                return JSON.parseObject(value, clazz);
            }
            return null;
        }

        private <T> List<T> getList(Cache<String, String> cache, String key, Class<T> clazz) {
            String value = cache.get(key);
            try {
                if (StringUtils.isNotBlank(value)) {
                    return JSON.parseArray(value, clazz);
                }
            } catch (Exception e) {
                log.error("getList error", e);
            }
            return null;
        }

        private void put(Cache<String, String> cache, String key, Object value) {
            cache.put(key, JSON.toJSONString(value));
        }

        private void remove(Cache<String, String> cache, String key) {
            cache.remove(key);
        }
    }

    private sealed interface CacheState permits AvailableCache, UnavailableCache {
    }

    private record AvailableCache(CacheManager cacheManager) implements CacheState {
    }

    private enum UnavailableCache implements CacheState {
        INSTANCE
    }
}
