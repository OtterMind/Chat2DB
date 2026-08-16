package ai.chat2db.plugin.presto;

import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;

import java.sql.Connection;

public class PrestoMetaData extends DefaultMetaService implements IDbMetaData {

    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        ISQLIdentifierProcessor identifierProcessor = getSQLIdentifierProcessor();
        String sql = buildShowCreateTableSql(identifierProcessor, databaseName, schemaName, tableName);
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
            return null;
        });
    }

    static String buildShowCreateTableSql(ISQLIdentifierProcessor identifierProcessor, String catalogName,
                                          String schemaName, String tableName) {
        if (isBlank(tableName)) {
            throw new IllegalArgumentException("Presto table name must not be blank");
        }

        boolean hasCatalog = !isBlank(catalogName);
        boolean hasSchema = !isBlank(schemaName);
        if (hasCatalog && !hasSchema) {
            throw new IllegalArgumentException("Presto schema name is required when catalog name is provided");
        }

        StringBuilder qualifiedName = new StringBuilder();
        if (hasCatalog) {
            qualifiedName.append(identifierProcessor.quoteIdentifierAlways(catalogName)).append('.');
        }
        if (hasSchema) {
            qualifiedName.append(identifierProcessor.quoteIdentifierAlways(schemaName)).append('.');
        }
        qualifiedName.append(identifierProcessor.quoteIdentifierAlways(tableName));
        return "SHOW CREATE TABLE " + qualifiedName;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
