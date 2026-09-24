package ai.chat2db.community.sqlx;

import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import com.alibaba.fastjson2.JSON;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Translates saved Chat2DB datasources into the versioned import document SQLX accepts.
 * <p>
 * Every entry is checked on its own so an engine SQLX does not speak, or a connection without the
 * fields the CLI needs, is reported with a reason instead of failing the whole import.
 */
public final class SqlxConnectionMapping {

    /** Reason codes the settings page shows for entries that never reach SQLX. */
    public static final String REASON_ENGINE = "engine_not_supported";
    public static final String REASON_PORT = "invalid_port";
    public static final String REASON_HOST = "missing_host";
    public static final String REASON_PATH = "invalid_path";

    private static final int DOCUMENT_VERSION = 1;
    private static final Set<String> FILE_ENGINES = Set.of("sqlite", "duckdb");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern TLS_REQUESTED = Pattern.compile(
            "(?i)(usessl=true|ssl=true|sslmode=(require|verify-ca|verify-full)|sslmode=required)");

    private SqlxConnectionMapping() {
    }

    /** The document SQLX reads, plus the entries this mapping had to leave out. */
    public record Document(String json, List<Map<String, Object>> skipped, int candidates, int entries) {
    }

    /** One mapped entry: the SQLX connection object, or the reason it cannot be imported. */
    public record Mapped(Map<String, Object> connection, String reason, String detail) {

        public boolean supported() {
            return connection != null;
        }
    }

    public static Document buildDocument(List<WorkspaceDataSource> sources) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        int candidates = sources == null ? 0 : sources.size();
        if (sources != null) {
            for (WorkspaceDataSource source : sources) {
                Mapped mapped = map(source);
                String name = negotiateName(source);
                if (!mapped.supported()) {
                    skipped.add(skip(name, mapped.reason(), mapped.detail()));
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("connection", mapped.connection());
                entries.add(entry);
            }
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", DOCUMENT_VERSION);
        document.put("mode", "merge");
        document.put("datasources", entries);
        return new Document(JSON.toJSONString(document), skipped, candidates, entries.size());
    }

    /** Map one datasource, or explain why SQLX cannot take it. */
    public static Mapped map(WorkspaceDataSource source) {
        if (source == null) {
            return new Mapped(null, REASON_ENGINE, "the datasource no longer exists");
        }
        String engine = engine(source.getType());
        if (engine == null) {
            return new Mapped(null, REASON_ENGINE,
                    "SQLX does not speak the Chat2DB type " + String.valueOf(source.getType()));
        }
        boolean fileBased = FILE_ENGINES.contains(engine)
                || ("h2".equals(engine) && isBlank(source.getHost()));
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("database_type", engine);
        connection.put("tls", tls(source.getUrl()));
        connection.put("username", string(source.getUser()));
        connection.put("password", string(source.getPassword()));
        connection.put("properties", new LinkedHashMap<String, String>());
        if (fileBased) {
            String path = localPath(source, engine);
            if (isBlank(path)) {
                return new Mapped(null, REASON_PATH, "the datasource has no database file");
            }
            connection.put("host", "");
            connection.put("port", 0);
            connection.put("database", path);
            connection.put("service", "");
            return new Mapped(connection, null, null);
        }
        if (isBlank(source.getHost())) {
            return new Mapped(null, REASON_HOST, "the datasource has no host");
        }
        Integer port = port(source);
        if (port == null) {
            return new Mapped(null, REASON_PORT,
                    "the datasource port is not a number: " + String.valueOf(source.getPort()));
        }
        connection.put("host", source.getHost().trim());
        connection.put("port", port);
        connection.put("database", database(source, engine));
        connection.put("service", "oracle".equals(engine) ? oracleService(source) : "");
        return new Mapped(connection, null, null);
    }

    /** SQLX name for a Chat2DB alias; SQLX rejects an empty name and a bare UUID. */
    public static String negotiateName(WorkspaceDataSource source) {
        String alias = source == null ? null : source.getAlias();
        String name = isBlank(alias) ? "" : alias.trim();
        if (name.isEmpty()) {
            String engine = source == null ? null : engine(source.getType());
            name = (engine == null ? "datasource" : engine)
                    + (source == null || isBlank(source.getHost()) ? "" : "-" + source.getHost().trim());
        }
        if (UUID.matcher(name).matches()) {
            name = "datasource-" + name;
        }
        return name;
    }

    /** SQLX engine name for a Chat2DB type, or {@code null} when SQLX has no worker for it. */
    static String engine(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.trim().toUpperCase(Locale.ROOT)) {
            case "MYSQL" -> "mysql";
            case "MARIADB" -> "mariadb";
            case "TIDB" -> "tidb";
            case "OCEANBASE", "OCEANBASE_ORACLE" -> "oceanbase";
            case "POSTGRESQL" -> "postgresql";
            case "COCKROACHDB" -> "cockroachdb";
            case "OPENGAUSS", "GAUSSDB" -> "opengauss";
            case "ORACLE" -> "oracle";
            case "SQLSERVER" -> "sqlserver";
            case "CLICKHOUSE" -> "clickhouse";
            case "STARROCKS" -> "starrocks";
            case "DORIS" -> "doris";
            case "TDENGINE" -> "tdengine";
            case "DM" -> "dameng";
            case "KINGBASE" -> "kingbase";
            case "REDIS" -> "redis";
            case "MONGODB" -> "mongodb";
            case "SQLITE" -> "sqlite";
            case "DUCKDB" -> "duckdb";
            case "H2" -> "h2";
            case "PRESTO" -> "presto";
            case "HIVE" -> "hive";
            case "KYLIN" -> "kylin";
            case "XUGUDB" -> "xugu";
            case "DB2" -> "db2";
            // Chat2DB spells Informix with an M; SQLX publishes the engine as informix.
            case "INFOMIX" -> "informix";
            case "SUNDB" -> "sundb";
            case "GBASE8S" -> "gbase8s";
            default -> null;
        };
    }

    /**
     * The fields SQLX reports for a stored connection, which is everything the CLI prints about it.
     * <p>
     * The CLI compares the whole record, credentials included, when it merges an import. Credentials are
     * never readable back, so the page can only compare the target: a matching engine and endpoint means
     * the connection is already there.
     */
    private static final List<String> TARGET_FIELDS =
            List.of("database_type", "host", "port", "database", "service");

    /** Whether SQLX already holds a connection with the same engine and endpoint. */
    public static boolean sameTarget(Map<String, Object> stored, Map<String, Object> requested) {
        if (stored == null || requested == null) {
            return false;
        }
        for (String field : TARGET_FIELDS) {
            if (!Objects.equals(comparable(stored.get(field)), comparable(requested.get(field)))) {
                return false;
            }
        }
        return true;
    }

    private static Object comparable(Object value) {
        return value instanceof String text ? text.trim() : value;
    }

    /** Chat2DB connects permissively, so TLS stays off unless the saved URL asks for it. */
    static String tls(String url) {
        return url != null && TLS_REQUESTED.matcher(url).find() ? "verify-full" : "disable";
    }

    private static Integer port(WorkspaceDataSource source) {
        String port = source.getPort();
        if (isBlank(port)) {
            return null;
        }
        try {
            int value = Integer.parseInt(port.trim());
            return value > 0 && value <= 65535 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String database(WorkspaceDataSource source, String engine) {
        if ("oracle".equals(engine)) {
            return firstNonBlank(source.getSid(), source.getServiceName(), urlPath(source.getUrl()));
        }
        return firstNonBlank(urlPath(source.getUrl()), source.getServiceName());
    }

    private static String oracleService(WorkspaceDataSource source) {
        return firstNonBlank(source.getServiceName(), source.getSid());
    }

    /**
     * Database name or file path carried by a JDBC URL.
     * <p>
     * Handles both shapes Chat2DB saves: {@code jdbc:mysql://host:3306/app} and the file engines,
     * {@code jdbc:sqlite:/data/app.db} or {@code jdbc:h2:file:/data/demo}.
     */
    static String urlPath(String url) {
        if (isBlank(url)) {
            return "";
        }
        String value = url.trim();
        if (value.regionMatches(true, 0, "jdbc:", 0, "jdbc:".length())) {
            value = value.substring("jdbc:".length());
        }
        if (value.contains("//")) {
            value = value.substring(value.indexOf("//") + 2);
            int slash = value.indexOf('/');
            value = slash < 0 ? "" : value.substring(slash + 1);
        } else {
            int colon = value.indexOf(':');
            value = colon < 0 ? value : value.substring(colon + 1);
        }
        if (value.regionMatches(true, 0, "file:", 0, "file:".length())) {
            value = value.substring("file:".length());
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int parameters = value.indexOf(';');
        if (parameters >= 0) {
            value = value.substring(0, parameters);
        }
        return value.trim();
    }

    /** Path of a file-backed datasource, taken from the saved URL. */
    static String localPath(WorkspaceDataSource source, String engine) {
        String path = urlPath(source.getUrl());
        if (isBlank(path)) {
            return "";
        }
        String prefix = "file:";
        if (path.regionMatches(true, 0, prefix, 0, prefix.length())) {
            path = path.substring(prefix.length());
        }
        return path.trim();
    }

    private static Map<String, Object> skip(String name, String reason, String detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("reason", reason == null ? "invalid_connection" : reason);
        entry.put("detail", detail == null ? "" : detail);
        return entry;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String string(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
