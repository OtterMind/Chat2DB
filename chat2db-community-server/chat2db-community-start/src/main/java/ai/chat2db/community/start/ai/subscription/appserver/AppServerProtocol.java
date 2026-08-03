package ai.chat2db.community.start.ai.subscription.appserver;

import java.util.Set;

/**
 * Pinned allowlist / denylist for Chat2DB's Codex app-server surface.
 * Unsupported operations are gated rather than invented.
 */
public final class AppServerProtocol {

    public static final String CLIENT_NAME = "chat2db_community";
    public static final String CLIENT_TITLE = "Chat2DB Community";
    public static final String CLIENT_VERSION = "0.1.0";

    /** Default max UTF-8 bytes for a single newline-delimited JSON-RPC frame. */
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 1_048_576;

    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_INITIALIZED = "initialized";
    public static final String METHOD_ACCOUNT_READ = "account/read";
    public static final String METHOD_ACCOUNT_LOGIN_START = "account/login/start";
    public static final String METHOD_ACCOUNT_LOGIN_CANCEL = "account/login/cancel";
    public static final String METHOD_ACCOUNT_LOGOUT = "account/logout";
    public static final String METHOD_MODEL_LIST = "model/list";
    public static final String METHOD_THREAD_START = "thread/start";
    public static final String METHOD_THREAD_RESUME = "thread/resume";
    public static final String METHOD_THREAD_READ = "thread/read";
    public static final String METHOD_TURN_START = "turn/start";
    public static final String METHOD_TURN_INTERRUPT = "turn/interrupt";

    public static final Set<String> ALLOWLISTED_REQUEST_METHODS = Set.of(
            METHOD_INITIALIZE,
            METHOD_ACCOUNT_READ,
            METHOD_ACCOUNT_LOGIN_START,
            METHOD_ACCOUNT_LOGIN_CANCEL,
            METHOD_ACCOUNT_LOGOUT,
            METHOD_MODEL_LIST,
            METHOD_THREAD_START,
            METHOD_THREAD_RESUME,
            METHOD_THREAD_READ,
            METHOD_TURN_START,
            METHOD_TURN_INTERRUPT
    );

    /** Notifications Chat2DB may emit (client -> server). */
    public static final Set<String> ALLOWLISTED_CLIENT_NOTIFICATIONS = Set.of(
            METHOD_INITIALIZED
    );

    /**
     * Methods that must never be invoked by Chat2DB. Presence of these in capability
     * surfaces is expected for a full app-server; Chat2DB denies them at the port.
     */
    public static final Set<String> DENIED_NATIVE_METHODS = Set.of(
            "command/exec",
            "command/exec/write",
            "command/exec/resize",
            "command/exec/terminate",
            "process/spawn",
            "process/writeStdin",
            "process/resizePty",
            "process/kill",
            "fs/readFile",
            "fs/writeFile",
            "fs/createDirectory",
            "fs/getMetadata",
            "fs/readDirectory",
            "fs/remove",
            "fs/copy",
            "fs/watch",
            "fs/unwatch",
            "thread/shellCommand",
            "skills/list",
            "skills/config/write",
            "plugin/install",
            "plugin/uninstall",
            "marketplace/add",
            "remoteControl/enable",
            "mcpServer/tool/call"
    );

    private AppServerProtocol() {
    }

    public static boolean isAllowlistedRequest(String method) {
        return method != null && ALLOWLISTED_REQUEST_METHODS.contains(method);
    }

    public static boolean isDeniedNative(String method) {
        return method != null && DENIED_NATIVE_METHODS.contains(method);
    }
}
