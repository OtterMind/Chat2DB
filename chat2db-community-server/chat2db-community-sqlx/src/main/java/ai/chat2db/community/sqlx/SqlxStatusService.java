package ai.chat2db.community.sqlx;

import ai.chat2db.community.tools.sqlx.SqlxException;

import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.tools.config.SystemSettingConstant;
import ai.chat2db.community.tools.sqlx.SqlxBridge;
import ai.chat2db.community.tools.sqlx.SqlxDatasourceState;
import ai.chat2db.community.tools.sqlx.SqlxStatus;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Desktop-side SQLX command line management: discovery, installation, update checks and updates.
 * <p>
 * The service never touches the SQLX data directory; it only installs and runs the executable, and
 * credentials never pass through it. Long operations run on one background thread and publish their
 * progress into the status the settings page polls.
 */
public final class SqlxStatusService implements SqlxBridge {

    private static final Duration PROBE_TTL = Duration.ofSeconds(2);
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration UPDATE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration IMPORT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);

    private static final String STEP_DOWNLOADING = "downloading";
    private static final String STEP_FAILED = "failed";

    /** Reads the saved connections that should be copied into SQLX. */
    @FunctionalInterface
    public interface DataSourceReader {
        List<WorkspaceDataSource> read(List<Long> datasourceIds);
    }

    private final SqlxSettingsStore settings;
    private final SqlxReleaseInstaller installer;
    private volatile DataSourceReader dataSources;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sqlx-desktop");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Probe probe;
    private volatile long probedAt;
    private volatile Map<String, Object> operation;
    private volatile String runningOperationId;

    public SqlxStatusService() {
        this(SqlxSettingsStore.system(), new SqlxReleaseInstaller(), ids -> List.of());
    }

    public SqlxStatusService(SqlxSettingsStore settings, SqlxReleaseInstaller installer) {
        this(settings, installer, ids -> List.of());
    }

    public SqlxStatusService(SqlxSettingsStore settings, SqlxReleaseInstaller installer, DataSourceReader dataSources) {
        this.settings = settings;
        this.installer = installer;
        this.dataSources = dataSources;
    }

    @Override
    public SqlxStatus status() {
        return snapshot();
    }

    @Override
    public SqlxStatus install(String operationId, String version) {
        SqlxPlatform platform = SqlxPlatform.current().orElseThrow(() -> new SqlxException(
                "sqlx.unsupported_platform",
                "SQLX publishes no prebuilt archive for " + System.getProperty("os.name") + " "
                        + System.getProperty("os.arch")));
        startOperation(operationId, "install", STEP_DOWNLOADING, 0);
        worker.submit(() -> {
            try {
                SqlxReleaseInstaller.Installed installed = installer.install(version, platform, new SqlxReleaseInstaller.Progress() {
                    @Override
                    public void step(String step) {
                        updateOperation(step, null, null);
                    }

                    @Override
                    public void downloading(long downloaded, long total) {
                        Integer percent = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : null;
                        updateOperation(STEP_DOWNLOADING, percent, null);
                    }
                }, cancelled::get);
                settings.set(SystemSettingConstant.SQLX_BINARY_PATH, installed.executable().toString());
                settings.set(SystemSettingConstant.SQLX_VERSION, installed.version());
                settings.set(SystemSettingConstant.SQLX_SHA256, installed.sha256());
                settings.set(SystemSettingConstant.SQLX_SOURCE, SqlxStatus.SOURCE_CHAT2DB);
                settings.set(SystemSettingConstant.SQLX_INSTALL_NOTE,
                        installed.note() == null ? "" : installed.note());
                storeLatest(runCheck(probe(true)));
                clearOperation();
            } catch (SqlxException exception) {
                failOperation(exception);
            } catch (RuntimeException exception) {
                failOperation(new SqlxException("sqlx.install_failed", String.valueOf(exception.getMessage()), exception));
            } finally {
                cancelled.set(false);
            }
        });
        return snapshot();
    }

    @Override
    public SqlxStatus update(String operationId) {
        Probe current = probe(true);
        if (!SqlxStatus.STATE_INSTALLED.equals(current.state())) {
            throw new SqlxException("sqlx.not_installed", "SQLX is not installed");
        }
        startOperation(operationId, "update", "updating", null);
        worker.submit(() -> {
            try {
                SqlxProcessRunner.Result result = SqlxProcessRunner.run(
                        current.path(), List.of("update", "install"), UPDATE_TIMEOUT);
                if (!result.succeeded()) {
                    failOperation(new SqlxException("sqlx.update_failed", describe(result)));
                    return;
                }
                storeLatest(runCheck(probe(true)));
                clearOperation();
            } catch (SqlxException exception) {
                failOperation(exception);
            } catch (RuntimeException exception) {
                failOperation(new SqlxException("sqlx.update_failed", String.valueOf(exception.getMessage()), exception));
            }
        });
        return snapshot();
    }

    @Override
    public SqlxStatus checkUpdate(String operationId) {
        storeLatest(runCheck(probe(false)));
        return snapshot();
    }

    /**
     * Runs the release check for the detected executable.
     * <p>
     * The stored result describes the version that was installed when the check ran, so an install or
     * an update repeats it: otherwise the page keeps offering the release it just applied.
     */
    private Map<String, Object> runCheck(Probe current) {
        if (!SqlxStatus.STATE_INSTALLED.equals(current.state())) {
            Map<String, Object> latest = new LinkedHashMap<>();
            latest.put("status", "unknown");
            return latest;
        }
        SqlxProcessRunner.Result result = SqlxProcessRunner.run(
                current.path(), List.of("update", "check"), CHECK_TIMEOUT);
        return parseCheckResult(result, current.version());
    }

    @Override
    public SqlxStatus cancel(String operationId) {
        if (operationId != null && operationId.equals(runningOperationId)) {
            cancelled.set(true);
        }
        return snapshot();
    }

    @Override
    public SqlxStatus useBinary(String path) {
        if (path == null || path.isBlank()) {
            throw new SqlxException("sqlx.invalid_binary", "the executable path is required");
        }
        Path candidate = Path.of(path.trim());
        Optional<String> version = SqlxExecutable.versionOf(candidate);
        if (version.isEmpty()) {
            throw new SqlxException("sqlx.invalid_binary",
                    "the selected file is not an OtterMind SQLX executable: " + path);
        }
        settings.set(SystemSettingConstant.SQLX_BINARY_PATH, candidate.toString());
        settings.set(SystemSettingConstant.SQLX_VERSION, version.get());
        settings.set(SystemSettingConstant.SQLX_SOURCE, SqlxStatus.SOURCE_EXTERNAL);
        probe(true);
        return snapshot();
    }

    /**
     * Attach the reader that supplies saved connections.
     * <p>
     * The bridge is registered before Spring starts so the settings page never sees a missing
     * bridge; the reader arrives with the Spring context.
     */
    public void attach(DataSourceReader reader) {
        this.dataSources = reader;
    }

    @Override
    public Map<String, Object> importDatasources(List<Long> datasourceIds) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            throw new SqlxException("sqlx.no_datasources", "select at least one datasource");
        }
        Probe current = probe(true);
        if (!SqlxStatus.STATE_INSTALLED.equals(current.state())) {
            throw new SqlxException("sqlx.not_installed", "SQLX is not installed");
        }
        SqlxConnectionMapping.Document document =
                SqlxConnectionMapping.buildDocument(dataSources.read(datasourceIds));
        if (document.candidates() == 0) {
            throw new SqlxException("sqlx.no_datasources", "none of the selected datasources still exists");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        if (document.entries() > 0) {
            SqlxProcessRunner.Result result = SqlxProcessRunner.run(
                    current.path(), List.of("datasource", "import", "--stdin"), IMPORT_TIMEOUT, document.json());
            if (!result.succeeded()) {
                throw new SqlxException("sqlx.import_failed", describe(result));
            }
            summary = parseImportReport(result.output());
        } else {
            summary.put("added", 0);
            summary.put("updated", 0);
            summary.put("unchanged", 0);
            summary.put("total", 0);
            summary.put("datasources", List.of());
            summary.put("skipped", List.of());
        }
        List<Object> skipped = new ArrayList<>(document.skipped());
        Object reported = summary.get("skipped");
        if (reported instanceof List<?> list) {
            skipped.addAll(list);
        }
        summary.put("skipped", skipped);
        return summary;
    }

    /**
     * Classify the selected Chat2DB datasources for the settings table.
     * <p>
     * One local {@code sqlx datasource list} answers whether a connection is already there; a datasource
     * whose engine has no SQLX worker, or whose record is missing a field, is reported with its reason.
     */
    @Override
    public List<SqlxDatasourceState> datasourceStates(List<Long> datasourceIds) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return List.of();
        }
        Map<Long, WorkspaceDataSource> saved = new LinkedHashMap<>();
        for (WorkspaceDataSource source : dataSources.read(datasourceIds)) {
            if (source != null && source.getId() != null) {
                saved.put(source.getId(), source);
            }
        }
        List<Map<String, Object>> stored = storedConnections(probe(false));
        List<SqlxDatasourceState> states = new ArrayList<>();
        for (Long id : datasourceIds) {
            WorkspaceDataSource source = saved.get(id);
            if (source == null) {
                states.add(state(id, SqlxDatasourceState.STATE_INCOMPLETE,
                        SqlxConnectionMapping.REASON_ENGINE, "the datasource no longer exists"));
                continue;
            }
            SqlxConnectionMapping.Mapped mapped = SqlxConnectionMapping.map(source);
            if (!mapped.supported()) {
                boolean knownEngine = !SqlxConnectionMapping.REASON_ENGINE.equals(mapped.reason());
                states.add(state(id,
                        knownEngine ? SqlxDatasourceState.STATE_INCOMPLETE : SqlxDatasourceState.STATE_UNSUPPORTED,
                        mapped.reason(), mapped.detail()));
                continue;
            }
            boolean imported = stored.stream()
                    .anyMatch(connection -> SqlxConnectionMapping.sameTarget(connection, mapped.connection()));
            states.add(state(id, imported ? SqlxDatasourceState.STATE_IMPORTED : SqlxDatasourceState.STATE_READY,
                    null, null));
        }
        return states;
    }

    private static SqlxDatasourceState state(Long id, String value, String reason, String detail) {
        SqlxDatasourceState state = new SqlxDatasourceState();
        state.setId(id);
        state.setState(value);
        state.setReason(reason);
        state.setDetail(detail);
        return state;
    }

    /** The connections SQLX already holds; an absent or unreadable store simply reports none. */
    private List<Map<String, Object>> storedConnections(Probe current) {
        if (!SqlxStatus.STATE_INSTALLED.equals(current.state())) {
            return List.of();
        }
        SqlxProcessRunner.Result result = SqlxProcessRunner.run(
                current.path(), List.of("datasource", "list"), LIST_TIMEOUT);
        if (!result.succeeded()) {
            return List.of();
        }
        List<Map<String, Object>> connections = new ArrayList<>();
        try {
            JSONObject root = JSON.parseObject(result.output());
            JSONObject data = root == null ? null : root.getJSONObject("data");
            JSONArray datasources = data == null ? null : data.getJSONArray("datasources");
            if (datasources != null) {
                for (int index = 0; index < datasources.size(); index++) {
                    JSONObject connection = datasources.getJSONObject(index).getJSONObject("connection");
                    if (connection != null) {
                        connections.add(new LinkedHashMap<>(connection));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // impl-contract: an unreadable store only means no connection is reported as imported.
            return List.of();
        }
        return connections;
    }

    /** Read the summary a `sqlx datasource import` call prints. */
    static Map<String, Object> parseImportReport(String output) {
        JSONObject data = null;
        try {
            JSONObject root = JSON.parseObject(output);
            data = root == null ? null : root.getJSONObject("data");
        } catch (RuntimeException ignored) {
            // impl-contract: unparsable output is reported as a failed import below.
        }
        if (data == null) {
            throw new SqlxException("sqlx.import_failed",
                    output == null || output.isBlank() ? "the SQLX import returned no result" : output.trim());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : List.of("added", "updated", "unchanged", "total")) {
            summary.put(key, data.getIntValue(key));
        }
        summary.put("datasources", arrayOrEmpty(data, "datasources"));
        summary.put("skipped", arrayOrEmpty(data, "skipped"));
        return summary;
    }

    private static List<Object> arrayOrEmpty(JSONObject data, String key) {
        JSONArray array = data.getJSONArray(key);
        return array == null ? List.of() : new ArrayList<>(array);
    }

    private SqlxStatus snapshot() {
        Probe current = probe(false);
        SqlxStatus status = new SqlxStatus();
        status.setPlatform(SqlxPlatform.rendererPlatform());
        status.setInstallDir(SqlxPlatform.defaultInstallDirectory().toString());
        status.setState(current.state());
        String note = settings.get(SystemSettingConstant.SQLX_INSTALL_NOTE);
        status.setMessage(current.message() != null ? current.message()
                : note == null || note.isBlank() ? null : note);
        if (current.path() != null) {
            status.setPath(current.path().toString());
        }
        if (current.version() != null) {
            status.setVersion(current.version());
        }
        if (current.source() != null) {
            status.setSource(current.source());
        }
        status.setOnPath(current.onPath());
        status.setLatest(reconcileLatest(latest(), current.version()));
        Map<String, Object> running = operation;
        if (running != null) {
            status.setOperation(new LinkedHashMap<>(running));
        }
        return status;
    }

    /**
     * A recorded "update available" only holds while the installed version is older than the release it
     * names; otherwise the page would offer an update that is already installed.
     */
    static Map<String, Object> reconcileLatest(Map<String, Object> latest, String installedVersion) {
        if (!"updateAvailable".equals(latest.get("status")) || installedVersion == null) {
            return latest;
        }
        String version = stringValue(latest.get("version"));
        if (version.isBlank() || compareVersions(version, installedVersion) > 0) {
            return latest;
        }
        Map<String, Object> corrected = new LinkedHashMap<>(latest);
        corrected.put("status", "upToDate");
        return corrected;
    }

    /** Discover the executable: the recorded path, then PATH, then the official location. */
    private synchronized Probe probe(boolean refresh) {
        if (!refresh && probe != null && System.currentTimeMillis() - probedAt < PROBE_TTL.toMillis()) {
            return probe;
        }
        Probe detected = detect();
        probe = detected;
        probedAt = System.currentTimeMillis();
        return detected;
    }

    private Probe detect() {
        String configured = settings.get(SystemSettingConstant.SQLX_BINARY_PATH);
        String executableName = SqlxPlatform.defaultExecutableName();
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        Path onPath = findOnPath(executableName);
        if (onPath != null) {
            candidates.add(onPath);
        }
        Path official = SqlxPlatform.defaultInstallDirectory().resolve(executableName);
        candidates.add(official);
        Path conflict = null;
        for (Path candidate : candidates) {
            Optional<String> version = SqlxExecutable.versionOf(candidate);
            if (version.isPresent()) {
                boolean recorded = configured != null && candidate.toString().equals(configured);
                String source = recorded
                        ? Objects.requireNonNullElse(settings.get(SystemSettingConstant.SQLX_SOURCE),
                                SqlxStatus.SOURCE_CHAT2DB)
                        : SqlxStatus.SOURCE_EXTERNAL;
                return new Probe(SqlxStatus.STATE_INSTALLED, candidate, version.get(), source, null,
                        isOnPath(candidate, executableName));
            }
            if (conflict == null && Files.exists(candidate)) {
                conflict = candidate;
            }
        }
        if (conflict != null) {
            return new Probe(SqlxStatus.STATE_CONFLICT, conflict, null, null,
                    "another program named " + executableName + " already exists at " + conflict, false);
        }
        if (SqlxPlatform.current().isEmpty()) {
            return new Probe(SqlxStatus.STATE_UNSUPPORTED, null, null, null, null, false);
        }
        return new Probe(SqlxStatus.STATE_NOT_INSTALLED, null, null, null, null, false);
    }

    private Path findOnPath(String executableName) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory).resolve(executableName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isOnPath(Path executable, String executableName) {
        Path onPath = findOnPath(executableName);
        return onPath != null && onPath.equals(executable);
    }

    private Map<String, Object> latest() {
        Map<String, Object> latest = new LinkedHashMap<>();
        String status = settings.get(SystemSettingConstant.SQLX_LATEST_STATUS);
        latest.put("status", status == null || status.isBlank() ? "unknown" : status);
        putIfPresent(latest, "version", settings.get(SystemSettingConstant.SQLX_LATEST_VERSION));
        String checkedAt = settings.get(SystemSettingConstant.SQLX_LATEST_CHECKED_AT);
        if (checkedAt != null && !checkedAt.isBlank()) {
            try {
                latest.put("checkedAt", Long.parseLong(checkedAt));
            } catch (NumberFormatException ignored) {
                // impl-contract: an unreadable timestamp simply leaves the time out of the snapshot.
            }
        }
        putIfPresent(latest, "error", settings.get(SystemSettingConstant.SQLX_LATEST_ERROR));
        return latest;
    }

    private void storeLatest(Map<String, Object> latest) {
        settings.set(SystemSettingConstant.SQLX_LATEST_STATUS, String.valueOf(latest.get("status")));
        settings.set(SystemSettingConstant.SQLX_LATEST_CHECKED_AT, String.valueOf(System.currentTimeMillis()));
        settings.set(SystemSettingConstant.SQLX_LATEST_VERSION, stringValue(latest.get("version")));
        settings.set(SystemSettingConstant.SQLX_LATEST_ERROR, stringValue(latest.get("error")));
    }

    static Map<String, Object> parseCheckResult(SqlxProcessRunner.Result result, String installedVersion) {
        Map<String, Object> latest = new LinkedHashMap<>();
        JSONObject data = null;
        try {
            JSONObject root = JSON.parseObject(result.output());
            data = root == null ? null : root.getJSONObject("data");
        } catch (RuntimeException ignored) {
            // impl-contract: unparsable output is reported as a failed check below.
        }
        if (data == null) {
            latest.put("status", "checkFailed");
            latest.put("error", describe(result));
            return latest;
        }
        String version = data.getString("latest_version");
        String status = data.getString("status");
        String error = data.getString("error");
        boolean newer = version != null && installedVersion != null
                && compareVersions(version, installedVersion) > 0;
        latest.put("status", newer ? "updateAvailable" : "upToDate");
        if (version != null) {
            latest.put("version", version);
        }
        if (status == null || "check_failed".equals(status)) {
            latest.put("status", "checkFailed");
            if (error != null) {
                latest.put("error", error);
            }
        }
        return latest;
    }

    private void startOperation(String operationId, String kind, String step, Integer percent) {
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("operationId", operationId);
        started.put("kind", kind);
        started.put("step", step);
        if (percent != null) {
            started.put("percent", percent);
        }
        operation = started;
        runningOperationId = operationId;
        cancelled.set(false);
    }

    private void updateOperation(String step, Integer percent, String message) {
        Map<String, Object> running = operation;
        if (running == null) {
            return;
        }
        Map<String, Object> updated = new LinkedHashMap<>(running);
        updated.put("step", step);
        if (percent != null) {
            updated.put("percent", percent);
        }
        if (message != null) {
            updated.put("message", message);
        }
        operation = updated;
    }

    private void failOperation(SqlxException exception) {
        Map<String, Object> running = operation;
        Map<String, Object> failed = running == null ? new LinkedHashMap<>() : new LinkedHashMap<>(running);
        failed.put("step", STEP_FAILED);
        failed.put("message", exception.getMessage());
        failed.put("code", exception.getCode());
        operation = failed;
        runningOperationId = null;
    }

    private void clearOperation() {
        operation = null;
        runningOperationId = null;
    }

    /** The stage of an installation or update, plus the discovered executable. */
    private record Probe(String state, Path path, String version, String source, String message, boolean onPath) {
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank() && !"null".equals(value)) {
            target.put(key, value);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String describe(SqlxProcessRunner.Result result) {
        String output = result.output().trim();
        if (result.timedOut()) {
            return "the SQLX command timed out";
        }
        return output.isEmpty() ? "the SQLX command failed with exit code " + result.exitCode() : output;
    }

    /** Compares release versions numerically, ignoring a pre-release suffix. */
    static int compareVersions(String left, String right) {
        String[] leftParts = releasePart(left).split("\\.");
        String[] rightParts = releasePart(right).split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? numeric(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? numeric(rightParts[index]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static String releasePart(String version) {
        String value = version == null ? "" : version.trim();
        int separator = value.indexOf('-');
        if (separator < 0) {
            separator = value.indexOf('+');
        }
        return separator < 0 ? value : value.substring(0, separator);
    }

    private static int numeric(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
