package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.service.db.IDbCustomDatabaseService;
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
                CUSTOM_DATABASES.put(dbType, config);
                try {
                    Chat2DBContext.registerConfigurableDatabase(config);
                } catch (RuntimeException e) {
                    // impl-contract: fallback - one unusable custom type must not block startup.
                    log.error("register custom database failed: {}", dbType, e);
                }
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
        validate(config);
        String dbType = normalize(config.getDbType());
        config.setDbType(dbType);
        if (StringUtils.isBlank(config.getName())) {
            config.setName(dbType);
        }
        // Re-registering replaces the previous definition, so an edit takes effect
        // without a restart; unregister first so a failed registration cannot leave
        // the old plugin bound to a type we have already overwritten on disk.
        Chat2DBContext.unregisterConfigurableDatabase(dbType);
        Chat2DBContext.registerConfigurableDatabase(config);
        CUSTOM_DATABASES.put(dbType, config);
        persist();
    }

    @Override
    public synchronized boolean deleteCustomDatabase(String dbType) {
        if (StringUtils.isBlank(dbType)) {
            return false;
        }
        String key = normalize(dbType);
        if (CUSTOM_DATABASES.remove(key) == null) {
            return false;
        }
        Chat2DBContext.unregisterConfigurableDatabase(key);
        persist();
        return true;
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
        for (DriverConfig driver : drivers) {
            if (StringUtils.isBlank(driver.getJdbcDriverClass())) {
                throw new BusinessException("custom.database.driverClassRequired");
            }
            if (StringUtils.isBlank(driver.getUrl())) {
                throw new BusinessException("custom.database.urlRequired");
            }
            driver.setDbType(dbType);
            driver.setCustom(true);
        }
        drivers.get(0).setDefaultDriver(true);
    }

    private static String normalize(String dbType) {
        return dbType.trim().toUpperCase(Locale.ROOT);
    }
}
