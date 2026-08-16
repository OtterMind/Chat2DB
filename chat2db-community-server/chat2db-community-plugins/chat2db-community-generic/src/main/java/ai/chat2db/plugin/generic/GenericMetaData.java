package ai.chat2db.plugin.generic;

import ai.chat2db.plugin.generic.identifier.GenericIdentifierProcessor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.constant.DBConfigConstants;
import ai.chat2db.spi.ConfigurableSQLIdentifierProcessor;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.model.async.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.DBStructUtils;
import org.apache.commons.collections4.CollectionUtils;

import java.sql.Connection;
import java.util.List;

public class GenericMetaData extends DefaultMetaService implements IDbMetaData {

    private final DBConfig injectedConfig;

    public GenericMetaData() {
        this(null);
    }

    public GenericMetaData(DBConfig dbConfig) {
        this.injectedConfig = dbConfig;
    }

    private DBConfig currentConfig() {
        if (injectedConfig != null) {
            return injectedConfig;
        }
        try {
            return Chat2DBContext.getDBConfig();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A dialect that declares {@code identifierQuotes} in its configuration gets a
     * processor built from that spec; everything else keeps the generic ANSI
     * behaviour of {@link GenericIdentifierProcessor} (conditional double quoting
     * plus string-literal escaping).
     */
    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        DBConfig config = currentConfig();
        ConfigurableSQLIdentifierProcessor configured = ConfigurableSQLIdentifierProcessor.fromSpec(
                config == null ? null : config.getIdentifierQuotes());
        return configured != null ? configured : GenericIdentifierProcessor.INSTANCE;
    }

    /**
     * JDBC getTables with the default type list also returns engine-internal
     * relations (Firebird MON$/RDB$, HSQLDB INFORMATION_SCHEMA views, ...);
     * browsing them fails or is meaningless, so the generic table listing keeps
     * user relations only.
     */
    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        List<Table> tables = super.tables(connection, databaseName, schemaName, tableName);
        if (CollectionUtils.isEmpty(tables)) {
            return tables;
        }
        return tables.stream()
                .filter(table -> !isSystemTableType(table.getType()))
                .collect(java.util.stream.Collectors.toList());
    }

    private static boolean isSystemTableType(String type) {
        return "SYSTEM TABLE".equalsIgnoreCase(type) || "SYSTEM VIEW".equalsIgnoreCase(type);
    }

    @Override
    public String getQualifiedTableName(String databaseName, String schemaName, String tableName) {
        DBConfig config = currentConfig();
        String policy = config == null ? null : config.getTableQualification();
        if ("table".equals(policy)) {
            return getMetaDataName(tableName);
        }
        if ("schema.table".equals(policy)) {
            return getMetaDataName(schemaName, tableName);
        }
        return getMetaDataName(databaseName, schemaName, tableName);
    }

    @Override
    public String getMetaDataName(String... names) {
        ISQLIdentifierProcessor processor = getSQLIdentifierProcessor();
        return java.util.Arrays.stream(names)
                .filter(org.apache.commons.lang3.StringUtils::isNotBlank)
                .map(processor::quoteIdentifier)
                .collect(java.util.stream.Collectors.joining("."));
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        String template = dbConfig.getSql(DBConfigConstants.SQL_TABLE_DDL);
        String templateDatabaseName = databaseName;
        String templateSchemaName = schemaName;
        String templateTableName = tableName;
        if (template != null) {
            templateDatabaseName = GenericSqlGuards.sanitizeTemplateValue(template, "{database}", databaseName);
            templateSchemaName = GenericSqlGuards.sanitizeTemplateValue(template, "{schema}", schemaName);
            templateTableName = GenericSqlGuards.sanitizeTemplateValue(template, "{table}", tableName);
        }
        String sql = dbConfig.getTableDdl(templateDatabaseName, templateSchemaName, templateTableName);
        String sqlResult = dbConfig.getTableDdlResult();
        String ddl = null;
        if (sql != null && sqlResult!=null) {
            ddl = DefaultSQLExecutor.getInstance().execute(connection, sql,
                    resultSet -> resultSet.next() ? resultSet.getString(sqlResult) : null);
        }
        if (ddl == null) {
            ddl = DBStructUtils.getTableDdl(connection, databaseName, schemaName, tableName);
        }
        return ddl;
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        List<ColumnType> columnTypes = dbConfig.getColumnTypes();
        if(CollectionUtils.isEmpty(columnTypes)){
            List<Type> types = types(Chat2DBContext.getConnection());
            columnTypes = IGenericMetaDataConverter.INSTANCE.type2columnType(types);
        }
        List<IndexType> indexTypes = dbConfig.getIndexTypes();
        TableMeta tableMeta = TableMeta.builder()
                .columnTypes(columnTypes)
                .indexTypes(indexTypes)
                .build();
        return tableMeta;
    }

}
