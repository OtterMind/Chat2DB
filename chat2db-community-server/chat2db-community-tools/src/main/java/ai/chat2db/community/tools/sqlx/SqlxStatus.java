package ai.chat2db.community.tools.sqlx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of the local SQLX command line as the desktop settings page shows it.
 * <p>
 * Field names match the renderer contract, so the values stay plain strings and numbers.
 */
public class SqlxStatus {

    /** No usable sqlx was found on this machine. */
    public static final String STATE_NOT_INSTALLED = "notInstalled";
    /** An OtterMind sqlx executable is available. */
    public static final String STATE_INSTALLED = "installed";
    /** The installation directory holds a different program named sqlx. */
    public static final String STATE_CONFLICT = "conflict";
    /** The platform has no published prebuilt archive. */
    public static final String STATE_UNSUPPORTED = "unsupported";

    public static final String SOURCE_CHAT2DB = "chat2db";
    public static final String SOURCE_EXTERNAL = "external";

    private String state = STATE_NOT_INSTALLED;

    private String platform;

    private String version;

    private String path;

    private String source;

    private boolean onPath;

    private String installDir;

    private Map<String, Object> latest = new LinkedHashMap<>();

    private Map<String, Object> operation;

    private String message;

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isOnPath() {
        return onPath;
    }

    public void setOnPath(boolean onPath) {
        this.onPath = onPath;
    }

    public String getInstallDir() {
        return installDir;
    }

    public void setInstallDir(String installDir) {
        this.installDir = installDir;
    }

    public Map<String, Object> getLatest() {
        return latest;
    }

    public void setLatest(Map<String, Object> latest) {
        this.latest = latest;
    }

    public Map<String, Object> getOperation() {
        return operation;
    }

    public void setOperation(Map<String, Object> operation) {
        this.operation = operation;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
