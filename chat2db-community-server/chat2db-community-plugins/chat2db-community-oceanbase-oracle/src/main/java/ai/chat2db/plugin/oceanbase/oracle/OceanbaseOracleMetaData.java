package ai.chat2db.plugin.oceanbase.oracle;

import ai.chat2db.plugin.oceanbase.oracle.identifier.OceanbaseOracleIdentifierProcessor;
import ai.chat2db.plugin.oracle.OracleMetaData;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static ai.chat2db.plugin.oceanbase.constant.OceanbaseOracleMetaDataConstants.*;
@Slf4j
public class OceanbaseOracleMetaData extends OracleMetaData implements IDbMetaData {

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return OceanbaseOracleIdentifierProcessor.INSTANCE;
    }


    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = buildTableDdlSql(tableName, schemaName);
        String tableCommentSql = buildTableCommentSql(schemaName, tableName);
        String tableColumnCommentSql = buildTableColumnCommentSql(schemaName, tableName);
        String PUIndexSql = buildPuIndexNameSql(schemaName, tableName);
        String tableIndexNameSql = buildTableIndexNameSql(schemaName, tableName);
        StringBuilder ddlBuilder = new StringBuilder();
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            try {
                if (resultSet.next()) {
                    ddlBuilder.append(resultSet.getString("sql")).append(";");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        DefaultSQLExecutor.getInstance().execute(connection, tableCommentSql, resultSet -> {
            if (resultSet.next()) {
                String tableComment = resultSet.getString("comments");
                if (StringUtils.isNotBlank(tableComment)) {
                    ddlBuilder.append(buildTableCommentDdl(schemaName, tableName, tableComment));
                }
            }
        });
        DefaultSQLExecutor.getInstance().execute(connection, tableColumnCommentSql, resultSet -> {
            while (resultSet.next()) {
                String columnName = resultSet.getString("column_name");
                String columnComment = resultSet.getString("comments");
                if (StringUtils.isNotBlank(columnComment)) {
                    ddlBuilder.append(buildColumnCommentDdl(schemaName, tableName, columnName, columnComment));
                }
            }
        });
        List<String> PUConstraintsName = DefaultSQLExecutor.getInstance().execute(connection, PUIndexSql, resultSet -> {
            List<String> PUIndexNames = new ArrayList<>();
            while (resultSet.next()) {
                String indexName = resultSet.getString("index_name");
                if (StringUtils.isNotBlank(indexName)) {
                    PUIndexNames.add(indexName);
                }
            }
            return PUIndexNames;
        });

        ArrayList<String> indexes = DefaultSQLExecutor.getInstance().execute(connection, tableIndexNameSql, resultSet -> {
            ArrayList<String> indexNames = new ArrayList<>();
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                if (CollectionUtils.isNotEmpty(PUConstraintsName) && PUConstraintsName.contains(indexName)) {
                    continue;
                }
                indexNames.add(indexName);
            }
            return indexNames;
        });
        for (String index : indexes) {
            String tableIndexSql = buildTableIndexDdlSql(index, schemaName);
            DefaultSQLExecutor.getInstance().execute(connection, tableIndexSql, resultSet -> {
                while (resultSet.next()) {
                    String ddl = resultSet.getString("ddl");
                    if (StringUtils.isNotBlank(ddl)) {
                        ddlBuilder.append("\n\n").append(ddl);
                    }
                }
            });
        }
        return ddlBuilder.toString();
    }

    static String buildTableDdlSql(String tableName, String schemaName) {
        return String.format(TABLE_DDL_SQL, OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(tableName),
                OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(schemaName));
    }

    static String buildTableCommentSql(String schemaName, String tableName) {
        return String.format(TABLE_COMMENT_SQL, OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(schemaName),
                OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(tableName));
    }

    static String buildTableColumnCommentSql(String schemaName, String tableName) {
        return String.format(TABLE_COLUMN_COMMENT_SQL, OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(schemaName),
                OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(tableName));
    }

    static String buildPuIndexNameSql(String schemaName, String tableName) {
        return String.format(PU_INDEX_NAME_SQL, OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(schemaName),
                OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(tableName));
    }

    static String buildTableIndexNameSql(String schemaName, String tableName) {
        return String.format(TABLE_INDEX_NAME_SQL, OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(schemaName),
                OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(tableName));
    }

    static String buildTableIndexDdlSql(String indexName, String schemaName) {
        return String.format(TABLE_INDEX_DDL_SQL, OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(indexName),
                OceanbaseOracleIdentifierProcessor.INSTANCE.escapeString(schemaName));
    }

    static String buildTableCommentDdl(String schemaName, String tableName, String comment) {
        return "\nCOMMENT ON TABLE "
                + OceanbaseOracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName) + "."
                + OceanbaseOracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName)
                + " IS " + OceanbaseOracleIdentifierProcessor.INSTANCE.quoteStringLiteral(comment) + ";";
    }

    static String buildColumnCommentDdl(String schemaName, String tableName, String columnName, String comment) {
        return "\nCOMMENT ON COLUMN "
                + OceanbaseOracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName) + "."
                + OceanbaseOracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName) + "."
                + OceanbaseOracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(columnName)
                + " IS " + OceanbaseOracleIdentifierProcessor.INSTANCE.quoteStringLiteral(comment) + ";";
    }


}
