package ai.chat2db.community.domain.core.cache;


import org.apache.commons.lang3.StringUtils;

public class CacheKey {

    public static String getLoginUserKey(Long userId) {
        return "login_user_" + userId;
    }

    public static String getDataSourceKey(Long dataSourceId) {
        return "schemas_datasourceId_" + dataSourceId;
    }

    public static String getDataBasesKey(Long dataSourceId) {
        return "databases_datasourceId_" + dataSourceId;
    }

    public static String getTablespacesKey(Long dataSourceId) {
        return "tablespaces_datasourceId_" + dataSourceId;
    }

    public static String getSchemasKey(Long dataSourceId, String databaseName) {

        return "databases_datasourceId_" + dataSourceId + "_databaseName_" + databaseName;
    }

    public static String getTableKey(Long dataSourceId, String databaseName, String schemaName) {
        StringBuffer stringBuffer = new StringBuffer("databases_datasourceId_" + dataSourceId);
        if (StringUtils.isNotBlank(databaseName)) {
            stringBuffer.append("_databaseName_" + databaseName);
        }
        if (StringUtils.isNotBlank(schemaName)) {
            stringBuffer.append("_schemaName_" + schemaName);
        }
        stringBuffer.append("_tables");
        return stringBuffer.toString();
    }

    public static String getViewKey(Long dataSourceId, String databaseName, String schemaName) {
        StringBuffer stringBuffer = new StringBuffer("databases_datasourceId_" + dataSourceId);
        if (StringUtils.isNotBlank(databaseName)) {
            stringBuffer.append("_databaseName_" + databaseName);
        }
        if (StringUtils.isNotBlank(schemaName)) {
            stringBuffer.append("_schemaName_" + schemaName);
        }
        stringBuffer.append("_views");
        return stringBuffer.toString();
    }

    public static String getColumnKey(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        StringBuffer stringBuffer = new StringBuffer("databases_datasourceId_" + dataSourceId);
        if (StringUtils.isNotBlank(databaseName)) {
            stringBuffer.append("_databaseName_" + databaseName);
        }
        if (StringUtils.isNotBlank(schemaName)) {
            stringBuffer.append("_schemaName_" + schemaName);
        }
        stringBuffer.append("_tableName_" + tableName);
        stringBuffer.append("_columns");
        return stringBuffer.toString();
    }

    public static String getConsoleParserKey(Long dataSourceId, Long consoleId) {
        StringBuilder keyBuilder = new StringBuilder(80);
        keyBuilder.append("console_parser_");
        keyBuilder.append("databases_datasourceId_").append(dataSourceId);

        if (consoleId != null) {
            keyBuilder.append("_consoleId_").append(consoleId);
        }
        return keyBuilder.toString();
    }
}
