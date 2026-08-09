package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.service.db.IDbCustomDatabaseService;
import ai.chat2db.community.domain.api.service.db.IDbJdbcDriverService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.spi.sql.Chat2DBContext;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Stores user-defined database types next to the custom driver configuration and
 * registers them with {@link Chat2DBContext} so they behave like a configured
 * generic database for the rest of the stack.
 */
@Slf4j
@Service
public class DbCustomDatabaseServiceImpl implements IDbCustomDatabaseService {

    private static final String CUSTOM_DATABASE_CONFIG_PATH = ConfigUtils.getEnvBasePath()
            + File.separator + "storage"
            + File.separator + "custom-database.json";

    private static final Map<String, DBConfig> CUSTOM_DATABASES = new ConcurrentSkipListMap<>();

    static {
        load();
    }

    private final IDbJdbcDriverService jdbcDriverService;

    public DbCustomDatabaseServiceImpl(IDbJdbcDriverService jdbcDriverService) {
        this.jdbcDriverService = jdbcDriverService;
    }

    private static void load() {
        try {
            if (!FileUtil.exist(CUSTOM_DATABASE_CONFIG_PATH)) {
                return;
            }
            String content = FileUtil.readUtf8String(CUSTOM_DATABASE_CONFIG_PATH);
            if (StringUtils.isBlank(content)) {
                return;
            }
            Map<String, DBConfig> stored = JSON.parseObject(content, new TypeReference<Map<String, DBConfig>>() {
            });
            if (stored == null) {
                return;
            }
            stored.forEach((dbType, config) -> {
                if (config == null || StringUtils.isBlank(dbType)) {
                    return;
                }
                String key = normalize(dbType);
                try {
                    Chat2DBContext.registerConfigurableDatabase(config);
                } catch (RuntimeException e) {
                    // impl-contract: fallback - one unusable custom type must not block startup.
                    // A type that cannot be registered is dropped rather than kept: a later
                    // release may ship a plugin for it, and keeping the dead definition would
                    // leave it listed but unusable, and persist it again on the next write.
                    log.error("dropping custom database that cannot be registered: {}", key, e);
                    return;
                }
                CUSTOM_DATABASES.put(key, config);
            });
        } catch (Exception e) {
            // impl-contract: fallback - invalid custom database config should not block service startup.
            log.error("load custom database config error", e);
        }
    }

    private static synchronized void persist() {
        FileUtil.writeUtf8String(JSON.toJSONString(CUSTOM_DATABASES), CUSTOM_DATABASE_CONFIG_PATH);
    }

    @Override
    public List<DBConfig> listCustomDatabases() {
        return new ArrayList<>(CUSTOM_DATABASES.values());
    }

    @Override
    public DBConfig queryCustomDatabase(String dbType) {
        return StringUtils.isBlank(dbType) ? null : CUSTOM_DATABASES.get(normalize(dbType));
    }

    @Override
    public synchronized void saveCustomDatabase(DBConfig config) {
        jdbcDriverService.requireDriverManagementSupported();
        validate(config);
        String dbType = normalize(config.getDbType());
        config.setDbType(dbType);
        if (StringUtils.isBlank(config.getName())) {
            config.setName(dbType);
        }
        // Registering replaces any previous definition, so an edit takes effect without
        // a restart. It must not be preceded by an unregister: registration can still
        // fail, and dropping the old binding first would leave a type that used to work
        // unusable until the next restart.
        Chat2DBContext.registerConfigurableDatabase(config);
        CUSTOM_DATABASES.put(dbType, config);
        persist();
        // The driver is cached by JAR name, so an edited driver class would otherwise
        // keep resolving to the previously loaded one.
        unloadDrivers(config);
    }

    @Override
    public synchronized boolean deleteCustomDatabase(String dbType) {
        jdbcDriverService.requireDriverManagementSupported();
        if (StringUtils.isBlank(dbType)) {
            return false;
        }
        String key = normalize(dbType);
        DBConfig removed = CUSTOM_DATABASES.remove(key);
        if (removed == null) {
            return false;
        }
        Chat2DBContext.unregisterConfigurableDatabase(key);
        persist();
        unloadDrivers(removed);
        return true;
    }

    /**
     * Drops the cached driver for every JAR the definition names, so the next
     * connection reloads the class from disk.
     */
    private void unloadDrivers(DBConfig config) {
        List<DriverConfig> drivers = config.getDriverConfigList();
        if (CollectionUtils.isEmpty(drivers)) {
            return;
        }
        for (DriverConfig driver : drivers) {
            if (StringUtils.isNotBlank(driver.getJdbcDriver())) {
                jdbcDriverService.unloadDriver(driver.getJdbcDriver());
            }
        }
    }

    private void validate(DBConfig config) {
        if (config == null || StringUtils.isBlank(config.getDbType())) {
            throw new BusinessException("custom.database.dbTypeRequired");
        }
        String dbType = normalize(config.getDbType());
        if (Chat2DBContext.isBuiltInDatabaseType(dbType)) {
            throw new BusinessException("custom.database.dbTypeConflict", new Object[]{dbType});
        }
        List<DriverConfig> drivers = config.getDriverConfigList();
        if (CollectionUtils.isEmpty(drivers)) {
            throw new BusinessException("custom.database.driverRequired");
        }
        boolean anyDefault = false;
        for (DriverConfig driver : drivers) {
            if (StringUtils.isBlank(driver.getJdbcDriverClass())) {
                throw new BusinessException("custom.database.driverClassRequired");
            }
            if (StringUtils.isBlank(driver.getUrl())) {
                throw new BusinessException("custom.database.urlRequired");
            }
            // Without a JAR name the driver cannot be looked up at connect time, and the
            // lookup map rejects a null key, so an unnamed JAR would surface as an opaque
            // connection error instead of a validation failure here.
            if (StringUtils.isBlank(driver.getJdbcDriver())) {
                throw new BusinessException("custom.database.driverJarRequired");
            }
            driver.setDbType(dbType);
            driver.setCustom(true);
            anyDefault |= driver.isDefaultDriver();
        }
        // Only fall back to the first entry when the caller flagged none: forcing it
        // unconditionally would flag two, and after a restart the first flagged entry
        // wins, silently changing which driver is the default.
        if (!anyDefault) {
            drivers.get(0).setDefaultDriver(true);
        }
    }

    private static String normalize(String dbType) {
        return dbType.trim().toUpperCase(Locale.ROOT);
    }
}
