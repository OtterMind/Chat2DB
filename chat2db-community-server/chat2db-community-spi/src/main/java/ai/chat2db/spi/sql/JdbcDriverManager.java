
package ai.chat2db.spi.sql;

import ai.chat2db.community.tools.exception.ConnectionException;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.spi.model.datasource.DriverEntry;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static ai.chat2db.community.tools.util.JdbcJarUtils.getFullPath;
import static ai.chat2db.community.tools.util.JdbcJarUtils.getNewFullPath;


public class JdbcDriverManager {
    private static final Logger log = LoggerFactory.getLogger(JdbcDriverManager.class);
    private static final Map<String, ClassLoader> CLASS_LOADER_MAP = new ConcurrentHashMap();
    private static final Map<String, DriverEntry> DRIVER_ENTRY_MAP = new ConcurrentHashMap();
    private static final String SQL_STATE_CODE = "08001";

    /**
     * Whitelist of allowed JDBC driver class package prefixes.
     *
     * <p>The class name supplied via {@code DriverConfig.jdbcDriverClass} is fully
     * attacker-controllable through the custom-driver save API. Without a
     * whitelist, an attacker who can drop a jar into {@code jdbc-lib/} (see
     * {@code /api/jdbc/driver/upload}) can name any class inside that jar and
     * have it instantiated here — including a class whose {@code <clinit>} runs
     * {@code Runtime.getRuntime().exec(...)}. This is the root of the two-stage
     * unauthenticated RCE affecting v0.3.0 → v5.3.1.</p>
     *
     * <p>The default list below is derived from every
     * {@code chat2db-community-plugins/*.json} so all built-in drivers keep
     * working. Operators can extend (never shrink) it via the system property
     * {@code chat2db.jdbc.allowed-class-prefixes} — comma-separated.</p>
     */
    private static final List<String> DEFAULT_ALLOWED_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "com.mysql.",
            "org.postgresql.",
            "oracle.jdbc.",
            "com.microsoft.sqlserver.",
            "org.sqlite.",
            "com.ibm.db2.",
            "org.h2.",
            "org.mariadb.",
            "com.clickhouse.",
            "ru.yandex.clickhouse.",
            "org.apache.hive.",
            "org.apache.druid.",
            "org.apache.kylin.",
            "org.opengauss.",
            "org.duckdb.",
            "org.elasticsearch.xpack.sql.",
            "com.aliyun.odps.",
            "com.databricks.",
            "org.mongodb.",
            "com.dbschema.",
            "com.datastax.",
            "org.neo4j.",
            "com.amazon.redshift.",
            "com.snowflake.",
            "net.snowflake.",
            "com.sap.db.",
            "com.oceanbase.",
            "com.kingbase8.",
            "com.gbasedbt.",
            "com.oscar.",
            "com.informix.",
            "com.simba.googlebigquery.",
            "com.taosdata.",
            "com.xugu.",
            "com.facebook.presto.",
            "csii.sundb.",
            "dm.jdbc.",
            "jdbc.RedisDriver"
    ));

    private static volatile List<String> allowedClassPrefixes;

    private static List<String> getAllowedClassPrefixes() {
        if (allowedClassPrefixes == null) {
            synchronized (JdbcDriverManager.class) {
                if (allowedClassPrefixes == null) {
                    List<String> prefixes = new ArrayList<>(DEFAULT_ALLOWED_PREFIXES);
                    String extra = System.getProperty("chat2db.jdbc.allowed-class-prefixes", "");
                    if (StringUtils.isNotBlank(extra)) {
                        for (String p : extra.split(",")) {
                            String trimmed = p.trim();
                            if (!trimmed.isEmpty() && !prefixes.contains(trimmed)) {
                                prefixes.add(trimmed);
                            }
                        }
                    }
                    allowedClassPrefixes = Collections.unmodifiableList(prefixes);
                }
            }
        }
        return allowedClassPrefixes;
    }

    /**
     * Throws {@link ConnectionException} if {@code className} is not under any
     * whitelisted prefix. This must run BEFORE any {@code loadClass(...)} call
     * so the malicious class is never resolved and its {@code <clinit>} never
     * fires.
     *
     * <p>ConnectionException extends BusinessException extends RuntimeException,
     * so this method does not need a {@code throws} clause — it can be called
     * from any context, including ones that only declare checked exceptions
     * like {@code IOException}.</p>
     */
    static void assertDriverClassAllowed(String className) {
        if (StringUtils.isBlank(className)) {
            throw new ConnectionException("driver.class.missing", null, null);
        }
        for (String prefix : getAllowedClassPrefixes()) {
            if (className.startsWith(prefix)) {
                return;
            }
        }
        log.warn("SECURITY: rejected loadClass for non-whitelisted JDBC driver " +
                "class '{}' — possible tainted upload attempt", className);
        throw new ConnectionException("driver.class.not.allowed",
                new Object[]{className}, null);
    }

    public static Connection getConnection(String url, DriverConfig driver) throws SQLException {
        Properties info = new Properties();
        return getConnection(url, info, driver);
    }

    public static Connection getConnection(String url, String user, String password, DriverConfig driver)
            throws SQLException {
        Properties info = new Properties();
        if (user != null) {
            info.put("user", user);
        }

        if (password != null) {
            info.put("password", password);
        }

        return getConnection(url, info, driver);
    }

    public static Connection getConnection(String url, String user, String password, DriverConfig driver,
                                           Map<String, Object> properties)
            throws SQLException {
        Properties info = new Properties();
        if (StringUtils.isNotEmpty(user)) {
            info.put("user", user);
        }

        if (StringUtils.isNotEmpty(password)) {
            info.put("password", password);
        }
        if (properties != null && !properties.isEmpty()) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    info.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return getConnection(url, info, driver);
    }

    public static Connection getConnection(String url, Properties info, DriverConfig driver)
            throws SQLException {
        if (Objects.isNull(url)) {
            throw new SQLException("The url cannot be null", SQL_STATE_CODE);
        }

        DriverEntry driverEntry = DRIVER_ENTRY_MAP.get(driver.getJdbcDriver());
        if (Objects.isNull(driverEntry)) {
            driverEntry = getJDBCDriver(driver);
        }
        Connection connection;
        try {
            connection = driverEntry.getDriver().connect(url, info);
            if (Objects.isNull(connection)) {
                throw new SQLException(String.format("driver.connect return null , No suitable driver found for url %s", url), SQL_STATE_CODE);

            }
            return connection;
        } catch (SQLException sqlException) {
            Connection con = tryConnectionAgain(driverEntry, url, info);

            if (Objects.isNull(con)) {
                throw new SQLException(String.format("Cannot create connection (%s)", sqlException.getMessage()), SQL_STATE_CODE,
                        sqlException);
            }

            return con;
        }
    }

    public static DriverPropertyInfo[] getProperty(DriverConfig driver)
            throws SQLException {
        if (Objects.isNull(driver)) {
            return null;
        }
        DriverEntry driverEntry = DRIVER_ENTRY_MAP.get(driver.getJdbcDriver());
        try {
            if (driverEntry == null) {
                driverEntry = getJDBCDriver(driver);
            }
            String url = Objects.isNull(driver.getUrl()) ? "" : driver.getUrl();
            return driverEntry.getDriver().getPropertyInfo(url, null);
        } catch (Exception var7) {
            return null;
        }
    }


    private static Connection tryConnectionAgain(DriverEntry driverEntry, String url,
                                                 Properties info) throws SQLException {
        if (url.contains("mysql")) {
            if (!info.containsKey("useSSL")) {
                info.put("useSSL", "false");
            }
            return driverEntry.getDriver().connect(url, info);
        }
        return null;
    }

    private static DriverEntry getJDBCDriver(DriverConfig driver)
            throws SQLException {
        // Layer-2 defence: reject non-whitelisted driver class names BEFORE any
        // classloading occurs. Without this guard, an attacker-controlled
        // jdbcDriverClass would be resolved via URLClassLoader.loadClass below,
        // triggering its <clinit> and yielding RCE.
        assertDriverClassAllowed(driver.getJdbcDriverClass());
        synchronized (driver) {
            try {
                if (DRIVER_ENTRY_MAP.containsKey(driver.getJdbcDriver())) {
                    return DRIVER_ENTRY_MAP.get(driver.getJdbcDriver());
                }
                ClassLoader cl = getClassLoader(driver);
                Driver d = (Driver) cl.loadClass(driver.getJdbcDriverClass()).newInstance();
                DriverEntry driverEntry = DriverEntry.builder().driverConfig(driver).driver(d).build();
                DRIVER_ENTRY_MAP.put(driver.getJdbcDriver(), driverEntry);
                return driverEntry;
            } catch (ConnectionException e) {
                // Whitelist rejection from getClassLoader/getURLClassLoader — propagate as-is.
                throw e;
            } catch (Exception e) {
                throw new ConnectionException("connection.driver.load.error", null, e);
            }
        }

    }

    public static ClassLoader getClassLoader(DriverConfig driverConfig) throws IOException, ClassNotFoundException {
        // Belt-and-braces: also enforce the whitelist here so future call sites
        // that skip getJDBCDriver cannot accidentally load arbitrary classes.
        assertDriverClassAllowed(driverConfig.getJdbcDriverClass());
        String jarPath = driverConfig.getJdbcDriver();
        if (CLASS_LOADER_MAP.containsKey(jarPath)) {
            return CLASS_LOADER_MAP.get(jarPath);
        } else {
            synchronized (jarPath) {
                if (CLASS_LOADER_MAP.containsKey(jarPath)) {
                    return CLASS_LOADER_MAP.get(jarPath);
                }
                URLClassLoader cl;
                try {
                    cl = getURLClassLoader(jarPath, driverConfig.getJdbcDriverClass(), false);
                } catch (ConnectionException e) {
                    // Whitelist rejection — do NOT retry with clean=true, just propagate.
                    throw e;
                } catch (Exception e) {
                    cl = getURLClassLoader(jarPath, driverConfig.getJdbcDriverClass(), true);
                }
                CLASS_LOADER_MAP.put(jarPath, cl);
                return cl;
            }
        }
    }

    private static String getFilePath(String jarPath, boolean clean) {
        return clean ? getNewFullPath(jarPath) : getFullPath(jarPath);
    }

    private static List<URL> getJarUrlsFromZip(String zipFilePath, boolean clean) throws IOException {
        List<URL> jarUrls = new ArrayList<>();
        String file = getFilePath(zipFilePath, clean);
        File unzipFile = new File(file);
        File[] files = unzipFile.listFiles();
        for (File f : files) {
            if (f.getName().endsWith(".jar")) {
                jarUrls.add(f.toURI().toURL());
            }
        }
        return jarUrls;
    }

    private static List<URL> getJarUrlsFromPaths(String[] jarPaths, boolean clean) throws IOException {
        List<URL> jarUrls = new ArrayList<>();
        for (String jarPath : jarPaths) {
            String file = getFilePath(jarPath, clean);
            File driverFile = new File(file);
            if (!driverFile.exists()) {
                throw new IOException("Driver jar file not found: " + jarPath
                        + ". Please re-upload the driver.");
            }
            jarUrls.add(driverFile.toURI().toURL());
        }
        return jarUrls;
    }


    public static void unload(String jdbcDriver) {
        if (StringUtils.isBlank(jdbcDriver)) {
            return;
        }
        ClassLoader removed = CLASS_LOADER_MAP.remove(jdbcDriver);
        DRIVER_ENTRY_MAP.remove(jdbcDriver);
        if (removed instanceof URLClassLoader) {
            try {
                ((URLClassLoader) removed).close();
            } catch (IOException e) {
                log.warn("close URLClassLoader failed for {}", jdbcDriver, e);
            }
        }
    }

    private static URLClassLoader getURLClassLoader(String jarPath, String clazz, boolean clean) throws IOException, ClassNotFoundException {
        // Final enforcement point: this method actually invokes loadClass below,
        // which is the instruction that fires a malicious class's <clinit>.
        assertDriverClassAllowed(clazz);
        String[] jarPaths = jarPath.split(",");
        List<URL> jarUrls;
        if (jarPath.endsWith(".zip")) {
            jarUrls = getJarUrlsFromZip(jarPath, clean);
        } else {
            jarUrls = getJarUrlsFromPaths(jarPaths, clean);
        }
        URL[] urls = jarUrls.toArray(new URL[0]);
        URLClassLoader classLoader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
        classLoader.loadClass(clazz);

        return classLoader;
    }

}
