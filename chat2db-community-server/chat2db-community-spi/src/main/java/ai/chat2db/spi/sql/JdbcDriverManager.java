
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ai.chat2db.community.tools.util.JdbcJarUtils.getFullPath;
import static ai.chat2db.community.tools.util.JdbcJarUtils.getNewFullPath;


public class JdbcDriverManager {
    private static final Logger log = LoggerFactory.getLogger(JdbcDriverManager.class);
    private static final Map<String, ClassLoader> CLASS_LOADER_MAP = new ConcurrentHashMap();
    private static final Map<String, DriverEntry> DRIVER_ENTRY_MAP = new ConcurrentHashMap();
    private static final String SQL_STATE_CODE = "08001";

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
        try {
            if (Objects.isNull(url)) {
                throw new SQLException("The url cannot be null", SQL_STATE_CODE);
            }

            DriverEntry driverEntry = DRIVER_ENTRY_MAP.get(driver.getJdbcDriver());
            if (Objects.isNull(driverEntry)) {
                driverEntry = getJDBCDriver(driver);
            }
            String connectionUrl = trustedConnectionUrl(url, driverEntry.getDriver());
            try {
                Connection connection = driverEntry.getDriver().connect(connectionUrl, info);
                if (Objects.isNull(connection)) {
                    throw new SQLException("driver.connect returned null; no suitable driver accepted the JDBC URL",
                            SQL_STATE_CODE);
                }
                return connection;
            } catch (SQLException sqlException) {
                Connection con;
                try {
                    con = tryConnectionAgain(driverEntry, connectionUrl, info);
                } catch (SQLException retryException) {
                    throw new SQLException(String.format("Cannot create connection (%s)",
                                    MySqlTlsTranslator.diagnosticMessage(retryException, info)), SQL_STATE_CODE,
                            retryException);
                }

                if (Objects.isNull(con)) {
                    throw new SQLException(String.format("Cannot create connection (%s)",
                                    MySqlTlsTranslator.diagnosticMessage(sqlException, info)), SQL_STATE_CODE,
                            sqlException);
                }

                return con;
            }
        } finally {
            MySqlTlsTranslator.cleanupTemporaryStores(info);
        }
    }

    private static String trustedConnectionUrl(String url, Driver driver) throws SQLException {
        if (!url.startsWith("jdbc:") || containsControlCharacter(url) || !driver.acceptsURL(url)) {
            throw new SQLException("The configured JDBC driver does not accept the connection URL", SQL_STATE_CODE);
        }
        return new String(url.toCharArray());
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
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
            // Only fall back to plaintext when no explicit TLS intent was expressed. A failed
            // TLS connection (sslMode / useSSL=true / trustCertificate* / clientCertificate*)
            // must not be retried with useSSL=false — on Connector/J 8.x that mixes the
            // deprecated useSSL with sslMode and on 5.1.x it silently disables the TLS the
            // user configured. See MySqlTlsTranslator.hasExplicitTlsIntent.
            if (!info.containsKey("useSSL") && !MySqlTlsTranslator.hasExplicitTlsIntent(info)) {
                info.put("useSSL", "false");
            }
            return driverEntry.getDriver().connect(url, info);
        }
        return null;
    }

    private static DriverEntry getJDBCDriver(DriverConfig driver)
            throws SQLException {
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
            } catch (Exception e) {
                throw new ConnectionException("connection.driver.load.error", null, e);
            }
        }

    }

    public static ClassLoader getClassLoader(DriverConfig driverConfig) throws IOException, ClassNotFoundException {
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
