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

    private static boolean init = false;

    private static CacheManager cacheManager;

    static {
        try {
            init();
        } catch (Exception e) {
            log.error("init error", e);
        }
    }

    private static void init() {
        cacheManager = CacheManagerBuilder.newCacheManagerBuilder()
                .with(CacheManagerBuilder.persistence(PATH))
                .withCache(CACHE, CacheConfigurationBuilder.newCacheConfigurationBuilder(String.class, String.class,
                        ResourcePoolsBuilder.newResourcePoolsBuilder()
                                .heap(10000, EntryUnit.ENTRIES)
                                .disk(2, MemoryUnit.GB, true)).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(4))))
                .build(true);
        init = true;
    }


    private static <T> T get(String key, Class<T> clazz) {
        Cache<String, String> myCache = cacheManager.getCache(CACHE, String.class, String.class);
        String value = myCache.get(key);
        if (!StringUtils.isEmpty(value)) {
            return JSON.parseObject(value, clazz);
        }
        return null;
    }


    private static <T> List<T> getList(String key, Class<T> clazz) {
        Cache<String, String> myCache = cacheManager.getCache(CACHE, String.class, String.class);
        String value = myCache.get(key);
        try {
            if (StringUtils.isNotBlank(value)) {
                return JSON.parseArray(value, clazz);
            }
        }catch (Exception e){
            log.error("getList error, removing corrupted cache entry: {}", key, e);
            remove(key);
        }
        return null;
    }

    public static <T> T get(String key, Class<T> clazz, Function<Object, Boolean> refresh,
                            Function<Object, T> function) {
        if (!init) {
            return function.apply(key);
        }
        T t;
        if (refresh.apply(key)) {
            remove(key);
            t = function.apply(key);
            put(key, t);
        } else {
            t = get(key, clazz);
            if (t == null) {
                t = function.apply(key);
                put(key, t);
            }
        }
        return t;
    }

    public static <T> List<T> getList(String key, Class<T> clazz, Function<Object, Boolean> refresh,
                                      Function<Object, List<T>> function) {
        if (!init) {
            return function.apply(key);
        }
        List<T> t;
        if (refresh.apply(key)) {
            remove(key);
            t = function.apply(key);
            put(key, t);
        } else {
            t = getList(key, clazz);
            if (t == null) {
                t = function.apply(key);
                put(key, t);
            }
        }
        return t;
    }

    private static void put(String s, Object value) {
        Cache<String, String> myCache = cacheManager.getCache(CACHE, String.class, String.class);
        myCache.put(s, JSON.toJSONString(value));
    }

    private static void remove(String key) {
        Cache<String, String> myCache = cacheManager.getCache(CACHE, String.class, String.class);
        myCache.remove(key);
    }

    public static void fuzzyDelete(String key) {
        if (!init) {
            return;
        }
        Cache<String, String> myCache = cacheManager.getCache(CACHE, String.class, String.class);
        try {
            Set<String> removes = new HashSet<>();
            myCache.forEach(entry -> {
                if (entry.getKey() != null && entry.getKey().startsWith(key) && !entry.getKey().equals(key)) {
                    removes.add(entry.getKey());
                }
            });
            myCache.removeAll(removes);
        } catch (Exception e) {
            FileUtil.del(PATH);
            init();
        }
    }


    public static void close() {
        log.info("close cache");
        try {
            cacheManager.close();
        } catch (Exception e) {
            log.error("", e);
        }
    }

}
