package ai.chat2db.community.tools.http;

import ai.chat2db.community.tools.util.ConfigUtils;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dtflys.forest.http.ForestCookie;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.math3.util.Pair;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
public class LocalCookie {

    private static Map<String, String> headers = new ConcurrentHashMap<>();

    private static Map<String, ForestCookie> cookies = new ConcurrentHashMap<>();

    private static final String HEADER_PATH = ConfigUtils.getBasePath()
            + File.separator
            + "cache" + File.separator + LocalStateNamespace.fileName(
                    "header",
                    StringUtils.defaultString(System.getProperty("spring.profiles.active"), "dev")
            );

    private static final String COOKIE_PATH = ConfigUtils.getBasePath()
            + File.separator
            + "cache" + File.separator + LocalStateNamespace.fileName(
                    "cookie",
                    StringUtils.defaultString(System.getProperty("spring.profiles.active"), "dev")
            );

    private static AtomicBoolean WRRITE_HEADER = new AtomicBoolean(false);

    private static AtomicBoolean WRRITE_COOKIE = new AtomicBoolean(false);

    static {
        try {
            init();
        } catch (Exception e) {
            log.error("LocalCookie init error", e);
        }
        Thread cookieFlushThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    if (WRRITE_HEADER.compareAndSet(true, false)) {
                        File headerFile = new File(HEADER_PATH);
                        FileUtil.writeUtf8String(JSON.toJSONString(headers), headerFile);
                    }
                    if (WRRITE_COOKIE.compareAndSet(true, false)) {
                        File CookieFile = new File(COOKIE_PATH);
                        FileUtil.writeUtf8String(JSON.toJSONString(cookies), CookieFile);
                    }
                } catch (Exception e) {
                    log.error("LocalCookie write file error", e);
                }
            }
        }, "chat2db-community-cookie-flush");
        cookieFlushThread.setDaemon(true);
        cookieFlushThread.start();
    }

    private static void init() {
        File headerFile = new File(HEADER_PATH);
        if (headerFile.exists()) {
            String str = FileUtil.readUtf8String(headerFile);
            if (StringUtils.isNotBlank(str)) {
                Map<String, String> map = JSON.parseObject(str, Map.class);
                if (map != null) {
                    headers.putAll(map);
                }
            }
        }

        File CookieFile = new File(COOKIE_PATH);
        if (CookieFile.exists()) {
            String str = FileUtil.readUtf8String(CookieFile);
            if (StringUtils.isNotBlank(str)) {
                Map<String, JSONObject> map = JSON.parseObject(str, Map.class);
                if (map != null) {
                    for (Map.Entry<String, JSONObject> entry : map.entrySet()) {
                        if (entry.getValue() == null || entry.getKey() == null) {
                            continue;
                        }
                        ForestCookie forestCookie = entry.getValue().toJavaObject(ForestCookie.class);
                        cookies.put(entry.getKey(), forestCookie);
                    }
                }
            }
        }
    }

    public static void addCookies(List<ForestCookie> forestCookies) {
        if (CollectionUtils.isEmpty(forestCookies)) {
            return;
        }
        forestCookies.forEach(forestCookie -> {
            if (forestCookie != null && StringUtils.isNotBlank(forestCookie.getName())) {
                cookies.put(forestCookie.getName(), forestCookie);
            }
        });
        WRRITE_COOKIE.set(true);
    }

    public static void addCookie(String key, String value) {
        if (key == null) {
            return;
        }
        ForestCookie forestCookie = new ForestCookie(key, value);
        forestCookie.setPath("/");
        forestCookie.setMaxAge(Duration.ofDays(365));
        addCookie(forestCookie);
    }

    public static void addCookie(ForestCookie forestCookie) {
        if (forestCookie == null || StringUtils.isBlank(forestCookie.getName())) {
            return;
        }
        cookies.put(forestCookie.getName(), forestCookie);
        WRRITE_COOKIE.set(true);
    }

    public static Map<String, ForestCookie> getCookies() {
        return cookies;
    }

    public static List<ForestCookie> getCookieList() {
        if (cookies.isEmpty()) {
            return Lists.newArrayList();
        }
        return cookies.values().stream().collect(Collectors.toList());
    }

    public static String getCookie(String key) {
        ForestCookie forestCookie = cookies.get(key);
        return forestCookie == null ? null : forestCookie.getValue();
    }

    public static void removeCookie(String key) {
        if (StringUtils.isNotBlank(key)) {
            cookies.remove(key);
        }
        WRRITE_COOKIE.set(true);
    }

    public static void removeCookies(String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        for (String key : keys) {
            if (StringUtils.isNotBlank(key)) {
                cookies.remove(key);
            }
        }
        WRRITE_COOKIE.set(true);
    }

    public static Map<String, String> getAllHeader(List<String> strings) {
        Map<String, String> headerMap = Maps.newHashMap();
        if (CollectionUtils.isEmpty(strings)) {
            return headerMap;
        } else {
            strings.forEach(h -> {
                String value = headers.get(h);
                if (StringUtils.isNotBlank(value)) {
                    headerMap.put(h, value);
                }
            });
        }
        return headerMap;
    }

    public static String getHeader(String key) {
        return headers.get(key);
    }

    public static void removeHeader(String key) {
        headers.remove(key);
        WRRITE_HEADER.set(true);
    }

    public static void setHeader(String key, String value) {
        headers.put(key, value);
        WRRITE_HEADER.set(true);
    }

    public static Pair<String, String> getOrganizationInfo(String key1, String key2) {
        Map<String, ForestCookie> cookieMap = getCookies();
        String organizationToken = getValue(key1, cookieMap);
        String organizationString = getValue(key2, cookieMap);
        return new Pair<>(organizationToken, organizationString);
    }

    private static String getValue(String key, Map<String, ForestCookie> cookieMap) {
        if (cookieMap == null) {
            return null;
        }
        return cookieMap.get(key) == null ? null : cookieMap.get(key).getValue();
    }
}
