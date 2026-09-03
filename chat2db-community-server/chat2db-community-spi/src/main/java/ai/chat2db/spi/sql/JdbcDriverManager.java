
package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.tools.exception.ConnectionException;
import ai.chat2db.spi.model.datasource.DriverEntry;
import org.apache.commons.collections4.CollectionUtils;
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
        return getConnection(url, info, driver.getDbType(), driver);
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

        return getConnection(url, info, driver.getDbType(), driver);
    }

    public static Connection getConnection(String url, String user, String password, DriverConfig driver,
                                           Map<String, Object> properties)
            throws SQLException {
        return getConnection(url, user, password, driver.getDbType(), driver, properties);
    }

    public static Connection getConnection(String url, String user, String password, String dbType,
                                           DriverConfig driver, Map<String, Object> properties)
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
        return getConnection(url, info, dbType, driver);
    }

    public static Connection getConnection(String url, Properties info, DriverConfig driver)
            throws SQLException {
        return getConnection(url, info, driver.getDbType(), driver);
    }

    private static Connection getConnection(String url, Properties info, String dbType, DriverConfig driver)
            throws SQLException {
        if (Objects.isNull(url)) {
            throw new SQLException("The url cannot be null", SQL_STATE_CODE);
        }

        DriverEntry driverEntry = DRIVER_ENTRY_MAP.get(driver.getJdbcDriver());
        if (Objects.isNull(driverEntry)) {
            driverEntry = getJDBCDriver(dbType, driver);
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
                driverEntry = getJDBCDriver(driver.getDbType(), driver);
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

    private static DriverEntry getJDBCDriver(String dbType, DriverConfig driver)
            throws SQLException {
        synchronized (driver) {
            try {
                if (DRIVER_ENTRY_MAP.containsKey(driver.getJdbcDriver())) {
                    return DRIVER_ENTRY_MAP.get(driver.getJdbcDriver());
                }
                ClassLoader cl = getClassLoader(dbType, driver);
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
        return getClassLoader(driverConfig.getDbType(), driverConfig);
    }

    public static ClassLoader getClassLoader(String dbType, DriverConfig driverConfig)
            throws IOException, ClassNotFoundException {
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
                    cl = getURLClassLoader(dbType, driverConfig, false);
                } catch (Exception e) {
                    cl = getURLClassLoader(dbType, driverConfig, true);
                }
                CLASS_LOADER_MAP.put(jarPath, cl);
                return cl;
            }
        }
    }

    private static String getFilePath(String jarPath, boolean clean, List<String> downloadUrls) {
        return clean ? getNewFullPath(jarPath, downloadUrls) : getFullPath(jarPath, downloadUrls);
    }

    private static List<URL> getJarUrlsFromZip(String zipFilePath, boolean clean,
                                               List<String> downloadUrls) throws IOException {
        List<URL> jarUrls = new ArrayList<>();
        String file = getFilePath(zipFilePath, clean, downloadUrls);
        File unzipFile = new File(file);
        File[] files = unzipFile.listFiles();
        for (File f : files) {
            if (f.getName().endsWith(".jar")) {
                jarUrls.add(f.toURI().toURL());
            }
        }
        return jarUrls;
    }

    private static List<URL> getJarUrlsFromPaths(String[] jarPaths, boolean clean,
                                                 List<String> downloadUrls) throws IOException {
        List<URL> jarUrls = new ArrayList<>();
        for (String jarPath : jarPaths) {
            String file = getFilePath(jarPath, clean, downloadUrls);
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

    private static URLClassLoader getURLClassLoader(String dbType, DriverConfig driverConfig, boolean clean)
            throws IOException, ClassNotFoundException {
        String jarPath = driverConfig.getJdbcDriver();
        List<String> downloadUrls = resolveTrustedDownloadUrls(dbType, driverConfig);
        String[] jarPaths = jarPath.split(",");
        List<URL> jarUrls;
        if (jarPath.endsWith(".zip")) {
            jarUrls = getJarUrlsFromZip(jarPath, clean, downloadUrls);
        } else {
            jarUrls = getJarUrlsFromPaths(jarPaths, clean, downloadUrls);
        }
        URL[] urls = jarUrls.toArray(new URL[0]);
        URLClassLoader classLoader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
        classLoader.loadClass(driverConfig.getJdbcDriverClass());

        return classLoader;
    }

    static List<String> resolveTrustedDownloadUrls(String dbType, DriverConfig requestedDriver) {
        if (StringUtils.isBlank(dbType) || requestedDriver == null
                || StringUtils.isBlank(requestedDriver.getJdbcDriver())
                || StringUtils.isBlank(requestedDriver.getJdbcDriverClass())) {
            return List.of();
        }
        DBConfig dbConfig;
        try {
            dbConfig = Chat2DBContext.getDBConfig(dbType);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if (dbConfig == null || CollectionUtils.isEmpty(dbConfig.getDriverConfigList())) {
            return List.of();
        }
        for (DriverConfig trustedDriver : dbConfig.getDriverConfigList()) {
            if (trustedDriver != null
                    && StringUtils.equals(requestedDriver.getJdbcDriver(), trustedDriver.getJdbcDriver())
                    && StringUtils.equals(requestedDriver.getJdbcDriverClass(), trustedDriver.getJdbcDriverClass())) {
                List<String> trustedUrls = trustedDriver.getDownloadJdbcDriverUrls();
                return CollectionUtils.isEmpty(trustedUrls) ? List.of() : List.copyOf(trustedUrls);
            }
        }
        return List.of();
    }

}
