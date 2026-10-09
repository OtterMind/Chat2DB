package ai.chat2db.community.web.api.model.http;


import ai.chat2db.community.tools.http.DesktopSessionContext;
import ai.chat2db.community.tools.http.LocalCookie;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.model.HeaderAndCookies;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.community.tools.util.RuntimeIdentityProvider;
import com.google.common.collect.Lists;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Pair;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class CookieUtil {

    public static final String CHAT2DB_ORGANIZATION_TOKEN = DesktopSessionContext.ORGANIZATION_TOKEN_COOKIE;

    public static final String CHAT2DB_ORGANIZATION_ID = DesktopSessionContext.ORGANIZATION_ID_COOKIE;

    public static final String CHAT2DB_USER_ID = "Chat2db-BS-TOKEN";

    public static final String COOKIE = "Cookie";

    public static final String CONTENT_TYPE = "Content-Type";

    public static final String SET_COOKIE = "Set-Cookie";

    public static final String CHAT2DB = "CHAT2DB";

    public static final String ACCEPT_LANGUAGE = "Accept-Language";

    public static final String TIME_ZONE = "Time-Zone";

    public static final String AUTHORIZATION = "Authorization";

    public static final List<String> headers = Lists.newArrayList(CHAT2DB_ORGANIZATION_ID, CHAT2DB_ORGANIZATION_TOKEN, CHAT2DB_USER_ID, COOKIE, CONTENT_TYPE, SET_COOKIE, CHAT2DB, ACCEPT_LANGUAGE, TIME_ZONE, AUTHORIZATION);

    public static Pair<String, String> getOrganizationInfo() {
        Optional<Pair<String, String>> fixedOrganizationInfo = RuntimeIdentityProvider.organizationInfo();
        if (fixedOrganizationInfo.isPresent()) {
            return fixedOrganizationInfo.get();
        }
        if (ConfigUtils.isDesktop()) {
            return LocalCookie.getOrganizationInfo(CHAT2DB_ORGANIZATION_TOKEN, CHAT2DB_ORGANIZATION_ID);
        }
        String organizationToken = null;
        String organizationString = null;
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (CHAT2DB_ORGANIZATION_TOKEN.equals(cookie.getName())) {
                        organizationToken = cookie.getValue();
                    } else if (CHAT2DB_ORGANIZATION_ID.equals(cookie.getName())) {
                        organizationString = cookie.getValue();
                    }
                }
            }
        }
        return new Pair<>(organizationToken, organizationString);
    }

    public static Long getUserIdCookie() {
        Optional<Long> fixedUserId = RuntimeIdentityProvider.userId();
        if (fixedUserId.isPresent()) {
            return fixedUserId.get();
        }
        if (ConfigUtils.isDesktop()) {
            String userIdBase64 = LocalCookie.getCookie(CHAT2DB_USER_ID);
            return decryptUserId(userIdBase64);
        }
        Long userId = null;
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (CHAT2DB_USER_ID.equals(cookie.getName())) {
                        String userIdBase64 = cookie.getValue();
                        userId = decryptUserId(userIdBase64);
                    }
                }
            }
        }
        return userId;
    }

    public static Long decryptUserId(String userIdBase64) {
        try {
            if (StringUtils.isBlank(userIdBase64)) {
                return null;
            }
            String userIdStr = new String(Base64.getDecoder().decode(userIdBase64), StandardCharsets.UTF_8);
            return Long.parseLong(userIdStr);
        } catch (Exception e) {
            return null;
        }
    }

    public static void removeCookie(String key) {
        if (ConfigUtils.isDesktop()) {
            LocalCookie.removeCookie(key);
            return;
        }
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            HttpServletResponse response = attributes.getResponse();
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (key.equals(cookie.getName())) {
                        cookie.setMaxAge(0);
                        cookie.setPath("/");
                        response.addCookie(cookie);
                    }
                }
            }
        }
    }

    public static void removeCookies(String... key) {
        if (key == null || key.length == 0) {
            return;
        }
        for (String s : key) {
            removeCookie(s);
        }
    }

    public static HeaderAndCookies getHeaderAndCookies() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HeaderAndCookies headerAndCookies = new HeaderAndCookies();
        headerAndCookies.setCookies(attributes.getRequest().getCookies());
        Enumeration<String> headerNames = attributes.getRequest().getHeaderNames();
        Map<String, String> headers = new HashMap<>();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = attributes.getRequest().getHeader(headerName);
            headers.put(headerName, headerValue);
        }
        headerAndCookies.setHeaders(headers);
        return headerAndCookies;
    }

    public static String getTimeZoneHeader() {
        if (ConfigUtils.isDesktop()) {
            return LocalCookie.getHeader(CookieUtil.TIME_ZONE);
        }
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            return attributes.getRequest().getHeader(CookieUtil.TIME_ZONE);
        }
        return null;
    }

    public static String getAcceptLanguage() {
        if (ConfigUtils.isDesktop()) {
            return LocalCookie.getHeader(ACCEPT_LANGUAGE);
        }
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            return attributes.getRequest().getHeader(ACCEPT_LANGUAGE);
        }
        return null;
    }

    public static void addCookie(String key, String value) {
        if (ConfigUtils.isDesktop()) {
            LocalCookie.addCookie(key, value);
            return;
        }
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            String sameSite = "Lax";
            HttpServletRequest request = attributes.getRequest();
            HttpServletResponse response = attributes.getResponse();
            Cookie cookie = new Cookie(key, value);
            cookie.setMaxAge(60 * 60 * 24 * 365);
            cookie.setPath("/");
            String serviceName = request.getServerName();
            if(StringUtils.isNotBlank(serviceName)){
                if(serviceName.toLowerCase().contains("chat2db-ai.com")){
                    cookie.setDomain(".chat2db-ai.com");
                    cookie.setSecure(true);
                    sameSite = "None";
                }else if(serviceName.toLowerCase().contains("chat2db.ai")){
                    cookie.setDomain(".chat2db.ai");
                    cookie.setSecure(true);
                    sameSite = "None";
                }
            }
            addSameSite(response ,cookie, sameSite);
        }
    }


    public static void addSameSite(HttpServletResponse response,Cookie cookie, String sameSite) {
        if(response == null || cookie == null ) {
            return;
        }
        StringBuilder cookieHeader = new StringBuilder();
        cookieHeader.append(cookie.getName()).append("=").append(cookie.getValue());

        if (cookie.getDomain() != null) {
            cookieHeader.append("; Domain=").append(cookie.getDomain());
        }

        if (cookie.getPath() != null) {
            cookieHeader.append("; Path=").append(cookie.getPath());
        }

        if (cookie.getMaxAge() > 0) {
            cookieHeader.append("; Max-Age=").append(cookie.getMaxAge());
        }

        if (cookie.getSecure()) {
            cookieHeader.append("; Secure");
        }

        if (cookie.isHttpOnly()) {
            cookieHeader.append("; HttpOnly");
        }
        if (sameSite != null && !sameSite.isEmpty()) {
            cookieHeader.append("; SameSite=").append(sameSite);
        }
        response.addHeader("Set-Cookie", cookieHeader.toString());
    }


}
