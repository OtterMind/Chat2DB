package ai.chat2db.community.web.api.util;

import org.apache.commons.lang3.StringUtils;

public final class AgentRuntimeDaemonUtils {

    public static final String API_PREFIX = "/api/agent/runtime/daemon";
    public static final String TOKEN_PROPERTY = "chat2db.agent.runtime.token";
    public static final String TOKEN_ENV = "CHAT2DB_AGENT_RUNTIME_TOKEN";
    public static final String RUN_LEASE_HEADER = "X-Chat2DB-Agent-Run-Lease";
    public static final String TASK_TOKEN_HEADER = "X-Chat2DB-Agent-Task-Token";

    private AgentRuntimeDaemonUtils() {
    }

    public static String runtimeToken() {
        String token = StringUtils.trimToNull(System.getenv(TOKEN_ENV));
        if (token != null) {
            return token;
        }
        return StringUtils.trimToNull(System.getProperty(TOKEN_PROPERTY));
    }
}
