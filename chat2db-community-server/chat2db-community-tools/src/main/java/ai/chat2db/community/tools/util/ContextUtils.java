package ai.chat2db.community.tools.util;

import ai.chat2db.community.tools.exception.NeedLoggedInBusinessException;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.model.HeaderAndCookies;
import ai.chat2db.community.tools.model.LoginUser;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
public class ContextUtils {

    private static final Map<Long, HeaderAndCookies> headerAndCookiesMap = new ConcurrentHashMap<>();


    private static final ThreadLocal<Context> CONTEXT_THREAD_LOCAL = new ThreadLocal<>();


    public static Long getUserId() {
        return getLoginUser().getId();
    }


    public static LoginUser getLoginUser() {
        Context context = queryContext();
        if (context != null && context.getLoginUser() != null) {
            return context.getLoginUser();
        }
        throw new NeedLoggedInBusinessException();
    }


    public static Context queryContext() {
        return CONTEXT_THREAD_LOCAL.get();
    }


    public static Context queryThreadContext() {
        return CONTEXT_THREAD_LOCAL.get();
    }


    public static void setContext(Context context) {
        CONTEXT_THREAD_LOCAL.set(context);
    }


    public static void removeContext() {
        CONTEXT_THREAD_LOCAL.remove();
    }

    public static void setHeaderAndCookies(Long orgId, HeaderAndCookies headerAndCookies) {
        headerAndCookiesMap.put(orgId, headerAndCookies);
    }

    public static HeaderAndCookies getHeaderAndCookies(Long orgId) {
        return headerAndCookiesMap.get(orgId);
    }

    public static void removeHeaderAndCookies(Long orgId) {
        headerAndCookiesMap.remove(orgId);
    }

    public static void clearHeaderAndCookies() {
        headerAndCookiesMap.clear();
    }

    public static Map<Long, HeaderAndCookies> getHeaderAndCookiesMap() {
        return headerAndCookiesMap;
    }

}
