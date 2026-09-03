package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.db.DbDriverConfigView;
import ai.chat2db.community.domain.api.service.db.IDbJdbcDriverService;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.JdbcJarUtils;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.JdbcDriverManager;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.map.MapUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

@Slf4j
@Service
public class DbJdbcDriverServiceImpl implements IDbJdbcDriverService {

    private static final String USER_HOME_ENV_PATH = ConfigUtils.getEnvBasePath();
    private static final String CUSTOM_DRIVER_CONFIG_PATH = USER_HOME_ENV_PATH
            + File.separator + "storage"
            + File.separator + "custom-driver.json";

    private static Map<String, List<DriverConfig>> driverConfigMap = new ConcurrentSkipListMap<>();

    static {
        try {
            if (!FileUtil.exist(USER_HOME_ENV_PATH) || !FileUtil.exist(CUSTOM_DRIVER_CONFIG_PATH)) {
                FileUtil.writeUtf8String("", CUSTOM_DRIVER_CONFIG_PATH);
            } else {
                String datasourceList = FileUtil.readUtf8String(CUSTOM_DRIVER_CONFIG_PATH);
                Map<String, List<DriverConfig>> loaded = new ConcurrentSkipListMap<>();
                if (StringUtils.isNotBlank(datasourceList)) {
                    Map<String, List<DriverConfig>> map =
                            JSON.parseObject(datasourceList, new TypeReference<Map<String, List<DriverConfig>>>() {
                            });
                    if (!MapUtil.isEmpty(map)) {
                        loaded.putAll(map);
                    }
                }
                driverConfigMap = loaded;
            }
        } catch (Exception e) { // impl-contract: fallback - invalid custom driver config should not block service startup.
            log.error("load custom driver config error", e);
        }
    }

    @Override
    public DBConfig queryDbConfig(String dbType) {
        return Chat2DBContext.PLUGIN_MAP.get(dbType).getDBConfig();
    }

    @Override
    public synchronized List<DriverConfig> queryCustomDrivers(String dbType) {
        List<DriverConfig> driverConfigs = driverConfigMap.get(dbType);
        return driverConfigs == null ? new ArrayList<>() : driverConfigs;
    }

    @Override
    public List<DriverConfig> queryAvailableDrivers(String dbType) {
        Map<String, DriverConfig> availableDrivers = new LinkedHashMap<>();
        List<DriverConfig> customDrivers = queryCustomDrivers(dbType);
        for (DriverConfig driverConfig : customDrivers) {
            driverConfig.setCustom(true);
            if (!driverExists(driverConfig)) {
                log.warn("Custom driver jar is missing");
            }
            availableDrivers.putIfAbsent(driverConfig.getJdbcDriver(), driverConfig);
        }

        DBConfig dbConfig = queryDbConfig(dbType);
        List<DriverConfig> driverConfigList = dbConfig.getDriverConfigList();
        if (driverConfigList != null) {
            for (DriverConfig driverConfig : driverConfigList) {
                if (!driverExists(driverConfig)) {
                    log.warn("Built-in driver jar is missing and was skipped");
                    continue;
                }
                availableDrivers.putIfAbsent(driverConfig.getJdbcDriver(), driverConfig);
            }
        }
        return availableDrivers.isEmpty() ? null : Lists.newArrayList(availableDrivers.values());
    }

    @Override
    public DbDriverConfigView queryDriverConfigView(String dbType) {
        return DbDriverConfigView.builder()
                .dbConfig(queryDbConfig(dbType))
                .availableDrivers(queryAvailableDrivers(dbType))
                .build();
    }

    @Override
    public void downloadBuiltinDrivers(String dbType) throws IOException {
        DBConfig dbConfig = queryDbConfig(dbType);
        List<DriverConfig> driverConfigList = dbConfig.getDriverConfigList();
        for (DriverConfig driverConfig : driverConfigList) {
            List<String> downloadJdbcDriverUrls = driverConfig.getDownloadJdbcDriverUrls();
            for (String downloadJdbcDriverUrl : downloadJdbcDriverUrls) {
                JdbcJarUtils.download(downloadJdbcDriverUrl);
            }
        }
    }

    @Override
    public void downloadBuiltinDriversOrThrow(String dbType) {
        try {
            downloadBuiltinDrivers(dbType);
        } catch (IOException e) {
            throw new BusinessException("jdbc.driver.downloadFailed", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public void downloadStartupDrivers() {
        List<String> urls = new ArrayList<>();
        Chat2DBContext.PLUGIN_MAP.forEach((k, v) -> {
            try {
                DBConfig dbConfig = v.getDBConfig();
                if (dbConfig != null) {
                    dbConfig.getDriverConfigList().forEach(driverConfig -> {
                        if (driverConfig != null && driverConfig.getDownloadJdbcDriverUrls() != null
                                && !driverConfig.getDownloadJdbcDriverUrls().isEmpty()
                                && "MYSQL".equals(driverConfig.getDbType())) {
                            urls.addAll(driverConfig.getDownloadJdbcDriverUrls());
                        }
                    });
                }
            } catch (Exception e) { // impl-contract: best-effort - one plugin config failure should not block other startup driver downloads.
                log.warn("load startup driver config failed, dbType={}", k, e);
            }
        });
        try {
            JdbcJarUtils.asyncDownload(urls);
        } catch (Exception e) { // impl-contract: best-effort - startup driver predownload should not block application startup.
            log.warn("async download startup drivers failed", e);
        }
    }

    @Override
    public synchronized void saveCustomDriver(DriverConfig driverConfig) {
        if (driverConfig == null || driverConfig.getDbType() == null) {
            return;
        }
        driverConfig.setCustom(true);
        Map<String, List<DriverConfig>> nextDriverConfigMap = new ConcurrentSkipListMap<>(driverConfigMap);
        List<DriverConfig> driverConfigs = new ArrayList<>(
                nextDriverConfigMap.getOrDefault(driverConfig.getDbType(), List.of()));
        driverConfigs.add(driverConfig);
        nextDriverConfigMap.put(driverConfig.getDbType(), driverConfigs);
        persistDriverConfigMap(nextDriverConfigMap);
        driverConfigMap = nextDriverConfigMap;
    }

    @Override
    public void saveCustomDriver(DriverConfig driverConfig, List<String> sourceDriverPaths) {
        requireDriverManagementSupported();
        if (driverConfig == null || StringUtils.isBlank(driverConfig.getDbType())
                || StringUtils.isBlank(driverConfig.getJdbcDriverClass())) {
            throw new BusinessException("jdbc.driver.uploadFailed", new Object[]{"invalid driver configuration"});
        }
        JdbcDriverManagementPolicy.PromotedDrivers promotedDrivers = ConfigUtils.isDesktop()
                ? null
                : JdbcDriverManagementPolicy.promoteUploadedDrivers(sourceDriverPaths,
                        Path.of(JdbcDriverConstants.DRIVER_UPLOAD_PATH),
                        Path.of(JdbcDriverConstants.DRIVER_LIB_PATH));
        String jdbcDriver = promotedDrivers == null ? copyDrivers(sourceDriverPaths) : promotedDrivers.jdbcDriver();
        driverConfig.setJdbcDriver(jdbcDriver);
        try {
            saveCustomDriver(driverConfig);
            unloadDriver(jdbcDriver);
        } catch (RuntimeException | Error exception) {
            if (promotedDrivers != null) {
                promotedDrivers.rollback();
            }
            throw exception;
        }
    }

    @Override
    // Desktop users intentionally select local JARs; web uploads use managed opaque upload tokens instead.
    @SuppressWarnings("lgtm[java/path-injection]")
    public String copyDrivers(List<String> driverPaths) {
        boolean exists = true;
        StringBuilder driverNames = new StringBuilder();
        for (String driverPath : driverPaths) {
            File file = new File(driverPath);
            if (!file.exists()) {
                exists = false;
                break;
            }
            File target = new File(JdbcDriverConstants.DRIVER_LIB_PATH + file.getName());
            FileUtil.copyFile(file, target, StandardCopyOption.REPLACE_EXISTING);
            driverNames.append(file.getName()).append(",");
        }
        if (!driverNames.isEmpty()) {
            driverNames.deleteCharAt(driverNames.length() - 1);
        }
        return exists ? driverNames.toString() : null;
    }

    @Override
    public synchronized DriverConfig deleteCustomDriver(String dbType, String jdbcDriver) {
        if (StringUtils.isBlank(dbType) || StringUtils.isBlank(jdbcDriver)) {
            return null;
        }
        List<DriverConfig> existingDriverConfigs = driverConfigMap.get(dbType);
        List<DriverConfig> driverConfigs = existingDriverConfigs == null
                ? null : new ArrayList<>(existingDriverConfigs);
        if (driverConfigs == null || driverConfigs.isEmpty()) {
            return null;
        }
        DriverConfig removed = null;
        for (int i = 0; i < driverConfigs.size(); i++) {
            DriverConfig dc = driverConfigs.get(i);
            if (jdbcDriver.equals(dc.getJdbcDriver())) {
                removed = driverConfigs.remove(i);
                break;
            }
        }
        if (removed == null) {
            return null;
        }
        Map<String, List<DriverConfig>> nextDriverConfigMap = new ConcurrentSkipListMap<>(driverConfigMap);
        if (driverConfigs.isEmpty()) {
            nextDriverConfigMap.remove(dbType);
        } else {
            nextDriverConfigMap.put(dbType, driverConfigs);
        }
        persistDriverConfigMap(nextDriverConfigMap);
        driverConfigMap = nextDriverConfigMap;
        return removed;
    }

    @Override
    public void deleteCustomDriver(String dbType, List<String> jdbcDrivers) {
        requireDriverManagementSupported();
        if (StringUtils.isBlank(dbType) || jdbcDrivers == null || jdbcDrivers.isEmpty()) {
            return;
        }
        String jdbcDriver = jdbcDrivers.get(0);
        DriverConfig removed = deleteCustomDriver(dbType, jdbcDriver);
        if (removed == null) {
            log.warn("Custom driver was not found");
            return;
        }
        deleteUnreferencedDriverJars(jdbcDriver);
        unloadDriver(jdbcDriver);
    }

    @Override
    // The requested identifier must first match an existing managed driver config before deletion is attempted.
    @SuppressWarnings("lgtm[java/path-injection]")
    public void deleteUnreferencedDriverJars(String jdbcDriver) {
        if (StringUtils.isBlank(jdbcDriver)) {
            return;
        }
        for (String jar : jdbcDriver.split(",")) {
            if (StringUtils.isBlank(jar) || isJarReferenced(jar)) {
                continue;
            }
            File file = new File(JdbcDriverConstants.DRIVER_LIB_PATH + jar);
            if (file.exists()) {
                try {
                    FileUtil.del(file);
                } catch (Exception e) {
                    log.warn("Delete driver jar file failed", e);
                }
            }
        }
    }

    @Override
    public synchronized boolean isJarReferenced(String jarName) {
        if (StringUtils.isBlank(jarName)) {
            return false;
        }
        for (List<DriverConfig> list : driverConfigMap.values()) {
            if (list == null) {
                continue;
            }
            for (DriverConfig dc : list) {
                if (dc == null || StringUtils.isBlank(dc.getJdbcDriver())) {
                    continue;
                }
                for (String j : dc.getJdbcDriver().split(",")) {
                    if (jarName.equals(j)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void unloadDriver(String jdbcDriver) {
        JdbcDriverManager.unload(jdbcDriver);
    }

    @Override
    public void requireDriverManagementSupported() {
        if (!JdbcDriverManagementPolicy.isSupported(ConfigUtils.isDesktop(), ConfigUtils.isCommunity())) {
            throw new BusinessException("web.not.support.db.type");
        }
    }

    private void persistDriverConfigMap(Map<String, List<DriverConfig>> nextDriverConfigMap) {
        Path configPath = Path.of(CUSTOM_DRIVER_CONFIG_PATH).toAbsolutePath().normalize();
        Path temporary = null;
        try {
            Files.createDirectories(configPath.getParent());
            temporary = Files.createTempFile(configPath.getParent(), ".custom-driver-", ".tmp");
            Files.writeString(temporary, JSON.toJSONString(nextDriverConfigMap));
            try {
                Files.move(temporary, configPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new BusinessException("common.businessError", null, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original persistence result remains authoritative.
                }
            }
        }
    }

    // Persisted driver entries are managed JAR identifiers, not arbitrary filesystem paths.
    @SuppressWarnings("lgtm[java/path-injection]")
    private boolean driverExists(DriverConfig driverConfig) {
        if (driverConfig == null || StringUtils.isBlank(driverConfig.getJdbcDriver())) {
            return false;
        }
        for (String jarPath : driverConfig.getJdbcDriver().split(",")) {
            File file = new File(JdbcDriverConstants.DRIVER_LIB_PATH + jarPath);
            if (!file.exists()) {
                return false;
            }
        }
        return true;
    }
}
