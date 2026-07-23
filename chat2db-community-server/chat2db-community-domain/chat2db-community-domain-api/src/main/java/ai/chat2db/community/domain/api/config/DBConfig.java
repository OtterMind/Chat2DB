
package ai.chat2db.community.domain.api.config;

import ai.chat2db.community.domain.api.constant.DBConfigConstants;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;


public class DBConfig {




    private String dbType;




    private String name;


    /**
     * Database type whose SQL dialect this database is compatible with. Used as
     * the fallback routing key for syntax plugins when no plugin is registered
     * for {@link #dbType} itself, so configuration-only databases can reuse an
     * existing dialect (for example a PostgreSQL-compatible engine).
     */
    private String sqlDialect;


    /**
     * Identifier quote characters for this dialect: a single quote string used
     * on both sides (for example {@code `}) or an {@code open:close} pair (for
     * example {@code [:]}). When absent, the ANSI double quote applies.
     */
    private String identifierQuotes;




    private DriverConfig defaultDriverConfig;




    private List<DriverConfig> driverConfigList;




    private String simpleCreateTable;




    private String simpleAlterTable;


    private boolean supportDatabase;


    private boolean supportSchema;




    private boolean preserveScriptBatchExecution;


    private List<ColumnType> columnTypes;

    private List<IndexType> indexTypes;


    private Map<String, String> sqlMap;


    private List<String> systemDatabases;

    private List<String> systemSchemas;

    public List<IndexType> getIndexTypes() {
        return indexTypes;
    }

    public void setIndexTypes(List<IndexType> indexTypes) {
        this.indexTypes = indexTypes;
    }

    public List<ColumnType> getColumnTypes() {
        return columnTypes;
    }

    public void setColumnTypes(List<ColumnType> columnTypes) {
        this.columnTypes = columnTypes;
    }

    public boolean isSupportDatabase() {
        return supportDatabase;
    }

    public void setSupportDatabase(boolean supportDatabase) {
        this.supportDatabase = supportDatabase;
    }

    public boolean isSupportSchema() {
        return supportSchema;
    }

    public void setSupportSchema(boolean supportSchema) {
        this.supportSchema = supportSchema;
    }

    public boolean isPreserveScriptBatchExecution() {
        return preserveScriptBatchExecution;
    }

    public void setPreserveScriptBatchExecution(boolean preserveScriptBatchExecution) {
        this.preserveScriptBatchExecution = preserveScriptBatchExecution;
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSqlDialect() {
        return sqlDialect;
    }

    public void setSqlDialect(String sqlDialect) {
        this.sqlDialect = sqlDialect;
    }

    public String getIdentifierQuotes() {
        return identifierQuotes;
    }

    public void setIdentifierQuotes(String identifierQuotes) {
        this.identifierQuotes = identifierQuotes;
    }

    public DriverConfig getDefaultDriverConfig() {
        if (this.defaultDriverConfig != null) {
            return this.defaultDriverConfig;
        } else {
            if (!CollectionUtils.isEmpty(driverConfigList)) {
                for (DriverConfig driverConfig : driverConfigList) {
                    if (driverConfig.isDefaultDriver()) {
                        return driverConfig;
                    }
                }
                return driverConfigList.get(0);
            }
        }
        return null;
    }

    public void setDefaultDriverConfig(DriverConfig defaultDriverConfig) {
        this.defaultDriverConfig = defaultDriverConfig;
    }

    public List<DriverConfig> getDriverConfigList() {
        return driverConfigList;
    }

    public void setDriverConfigList(List<DriverConfig> driverConfigList) {
        this.driverConfigList = driverConfigList;
        if (!CollectionUtils.isEmpty(driverConfigList)) {
            for (DriverConfig driverConfig : driverConfigList) {
                if (driverConfig.isDefaultDriver()) {
                    this.defaultDriverConfig = driverConfig;
                    break;
                }
            }
        }
    }

    public String getSimpleCreateTable() {
        return simpleCreateTable;
    }

    public void setSimpleCreateTable(String simpleCreateTable) {
        this.simpleCreateTable = simpleCreateTable;
    }

    public String getSimpleAlterTable() {
        return simpleAlterTable;
    }

    public void setSimpleAlterTable(String simpleAlterTable) {
        this.simpleAlterTable = simpleAlterTable;
    }

    public Map<String, String> getSqlMap() {
        return sqlMap;
    }

    public void setSqlMap(Map<String, String> sqlMap) {
        this.sqlMap = sqlMap;
    }

    public String getSql(String key) {
        if (sqlMap == null) {
            return null;
        }
        return sqlMap.get(key);
    }

    public String getTableDdlResult() {
        return getSql(DBConfigConstants.SQL_TABLE_DDL_RESULT);
    }


    public String getTableDdl(String databaseName, String schemaName, String tableName) {
        String template = getSql(DBConfigConstants.SQL_TABLE_DDL);
        if (template == null) {
            return null;
        }
        if (StringUtils.isNotBlank(databaseName)) {
            template = template.replace("{database}", databaseName);
        }
        if (StringUtils.isNotBlank(schemaName)) {
            template = template.replace("{schema}", schemaName);
        }
        if (StringUtils.isNotBlank(tableName)) {
            template = template.replace("{table}", tableName);
        }
        return template;
    }

    public String getViewDdl(String databaseName, String schemaName, String tableName) {
        String template = getSql(DBConfigConstants.SQL_VIEW_DDL);
        if (template == null) {
            return null;
        }
        if (StringUtils.isNotBlank(databaseName)) {
            template = template.replace("{database}", databaseName);
        }
        if (StringUtils.isNotBlank(schemaName)) {
            template = template.replace("{schema}", schemaName);
        }
        if (StringUtils.isNotBlank(tableName)) {
            template = template.replace("{table}", tableName);
        }
        return template;
    }

    public String getChangeDatabase(String databaseName,String schemaName) {
        String template = getSql(DBConfigConstants.SQL_CHANGE_DATABASE);
        if (template == null) {
            return null;
        }
        if (StringUtils.isNotBlank(databaseName)) {
            template = template.replace("{database}", databaseName);
        }
        if (StringUtils.isNotBlank(schemaName)) {
            template = template.replace("{schema}", schemaName);
        }
        return template;
    }

    public List<String> getSystemDatabases() {
        return systemDatabases;
    }

    public void setSystemDatabases(List<String> systemDatabases) {
        this.systemDatabases = systemDatabases;
    }

    public List<String> getSystemSchemas() {
        return systemSchemas;
    }

    public void setSystemSchemas(List<String> systemSchemas) {
        this.systemSchemas = systemSchemas;
    }

}
