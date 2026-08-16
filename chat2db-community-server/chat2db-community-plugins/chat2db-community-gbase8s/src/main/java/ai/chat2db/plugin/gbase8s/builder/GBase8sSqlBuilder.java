package ai.chat2db.plugin.gbase8s.builder;

import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.constant.SQLConstants;
import org.apache.commons.lang3.StringUtils;

public class GBase8sSqlBuilder extends DefaultSqlBuilder {

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        if (identifiers == null || identifiers.length == 0) {
            return SQLConstants.EMPTY;
        }
        if (identifiers.length == 1) {
            return buildQualifiedTableName(null, null, identifiers[0]);
        }
        if (identifiers.length == 2) {
            return buildQualifiedTableName(identifiers[0], null, identifiers[1]);
        }
        return buildQualifiedTableName(identifiers[0], identifiers[identifiers.length - 2],
                identifiers[identifiers.length - 1]);
    }

    @Override
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        script.append(buildQualifiedTableName(databaseName, schemaName, tableName));
    }

    private static String buildQualifiedTableName(String databaseName, String schemaName, String tableName) {
        StringBuilder name = new StringBuilder();
        boolean hasDatabase = StringUtils.isNotBlank(databaseName);
        boolean hasSchema = StringUtils.isNotBlank(schemaName);
        boolean hasTable = StringUtils.isNotBlank(tableName);
        if (hasDatabase) {
            name.append(databaseName);
            if (hasSchema || hasTable) {
                name.append(SQLConstants.COLON);
            }
        }
        if (hasSchema) {
            name.append(schemaName);
            if (hasTable) {
                name.append(SQLConstants.DOT);
            }
        }
        if (hasTable) {
            name.append(tableName);
        }
        return name.toString();
    }
}
