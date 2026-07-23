package ai.chat2db.plugin.generic;

import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.spi.DefaultMetaService;
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

    @Override
    public ai.chat2db.spi.ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        DBConfig config = currentConfig();
        ai.chat2db.spi.ConfigurableSQLIdentifierProcessor configured =
                ai.chat2db.spi.ConfigurableSQLIdentifierProcessor.fromSpec(
                        config == null ? null : config.getIdentifierQuotes());
        return configured != null ? configured : super.getSQLIdentifierProcessor();
    }

    @Override
    public String getMetaDataName(String... names) {
        ai.chat2db.spi.ISQLIdentifierProcessor processor = getSQLIdentifierProcessor();
        return java.util.Arrays.stream(names)
                .filter(org.apache.commons.lang3.StringUtils::isNotBlank)
                .map(processor::quoteIdentifier)
                .collect(java.util.stream.Collectors.joining("."));
    }

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        String sql = dbConfig.getTableDdl(databaseName, schemaName, tableName);
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
