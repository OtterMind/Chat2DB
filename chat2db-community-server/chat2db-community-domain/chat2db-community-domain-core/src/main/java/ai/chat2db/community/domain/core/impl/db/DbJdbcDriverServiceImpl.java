package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.db.DbDriverConfigView;
import ai.chat2db.community.domain.api.service.db.IDbJdbcDriverService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.JdbcJarUtils;
import ai.chat2db.spi.IPlugin;
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
import java.nio.file.StandardCopyOption;
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
        IPlugin plugin = Chat2DBContext.PLUGIN_MAP.get(dbType);
        if (plugin == null) {
            // A user-defined type can be removed while a datasource still references it,
            // so an unknown type has to read as a business error rather than an NPE.
            throw new BusinessException("custom.database.notRegistered", new Object[]{dbType});
        }
        return plugin.getDBConfig();
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
                log.warn("Custom driver jar missing: {}", driverConfig.getJdbcDriver());
            }
            availableDrivers.putIfAbsent(driverConfig.getJdbcDriver(), driverConfig);
        }

        DBConfig dbConfig = queryDbConfig(dbType);
        List<DriverConfig> driverConfigList = dbConfig.getDriverConfigList();
        if (driverConfigList != null) {
            for (DriverConfig driverConfig : driverConfigList) {
                if (!driverExists(driverConfig)) {
                    log.warn("Built-in driver jar missing, skipped: {}", driverConfig.getJdbcDriver());
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
        List<DriverConfig> driverConfigs = driverConfigMap.get(driverConfig.getDbType());
        if (driverConfigs == null) {
            driverConfigs = new ArrayList<>();
        }
        driverConfigs.add(driverConfig);
        driverConfigMap.put(driverConfig.getDbType(), driverConfigs);
        FileUtil.writeUtf8String(JSON.toJSONString(driverConfigMap), CUSTOM_DRIVER_CONFIG_PATH);
    }

    @Override
    public void saveCustomDriver(DriverConfig driverConfig, List<String> sourceDriverPaths) {
        requireDriverManagementSupported();
        String jdbcDriver = copyDrivers(sourceDriverPaths);
        driverConfig.setJdbcDriver(jdbcDriver);
        saveCustomDriver(driverConfig);
        unloadDriver(jdbcDriver);
    }

    @Override
    public String copyDrivers(List<String> driverPaths) {
        boolean exists = true;
        StringBuilder driverNames = new StringBuilder();
        for (String driverPath : driverPaths) {
            File file = new File(driverPath);
            if (!file.exists()) {
                exists = false;
                break;
            }
            File target = driverFile(file.getName());
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
        List<DriverConfig> driverConfigs = driverConfigMap.get(dbType);
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
        if (driverConfigs.isEmpty()) {
            driverConfigMap.remove(dbType);
        }
        FileUtil.writeUtf8String(JSON.toJSONString(driverConfigMap), CUSTOM_DRIVER_CONFIG_PATH);
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
            log.warn("Custom driver not found, dbType={}, jdbcDriver={}", dbType, jdbcDriver);
            return;
        }
        deleteUnreferencedDriverJars(jdbcDriver);
        unloadDriver(jdbcDriver);
    }

    @Override
    public void deleteUnreferencedDriverJars(String jdbcDriver) {
        if (StringUtils.isBlank(jdbcDriver)) {
            return;
        }
        for (String jar : jdbcDriver.split(",")) {
            if (StringUtils.isBlank(jar) || isJarReferenced(jar)) {
                continue;
            }
            File file = driverFile(jar);
            if (file.exists()) {
                try {
                    FileUtil.del(file);
                } catch (Exception e) {
                    log.warn("Delete driver jar file failed: {}", file.getAbsolutePath(), e);
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
        if (!ConfigUtils.isDesktop()) {
            throw new BusinessException("web.not.support.db.type");
        }
    }

    private boolean driverExists(DriverConfig driverConfig) {
        if (driverConfig == null || StringUtils.isBlank(driverConfig.getJdbcDriver())) {
            return false;
        }
        for (String jarPath : driverConfig.getJdbcDriver().split(",")) {
            File file = driverFile(jarPath);
            if (!file.exists()) {
                return false;
            }
        }
        return true;
    }

    private File driverFile(String jarPath) {
        try {
            return JdbcJarUtils.driverFile(jarPath);
        } catch (IOException e) {
            throw new BusinessException("jdbc.driver.invalidFileName", new Object[]{jarPath}, e);
        }
    }
}
