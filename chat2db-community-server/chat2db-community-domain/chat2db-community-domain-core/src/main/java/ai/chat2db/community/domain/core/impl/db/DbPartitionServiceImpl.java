package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbPartitionService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MySQL partition inspection and maintenance (MYSQL-OBJ-009). Works on 5.7/8.0 across
 * RANGE/RANGE COLUMNS/LIST/LIST COLUMNS/HASH/LINEAR HASH/KEY/LINEAR KEY.
 */
@Service
public class DbPartitionServiceImpl implements IDbPartitionService {

    private static final String SQL_PARTITIONS =
            "SELECT PARTITION_NAME, SUBPARTITION_NAME, PARTITION_ORDINAL_POSITION, "
                    + "PARTITION_METHOD, SUBPARTITION_METHOD, PARTITION_EXPRESSION, "
                    + "SUBPARTITION_EXPRESSION, PARTITION_DESCRIPTION, TABLE_ROWS, "
                    + "DATA_LENGTH, INDEX_LENGTH, PARTITION_COMMENT "
                    + "FROM information_schema.PARTITIONS "
                    + "WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' "
                    + "ORDER BY PARTITION_ORDINAL_POSITION";

    private static final String TABLE_NOT_PARTITIONED = "table.notPartitioned";

    @Override
    public List<Map<String, Object>> list(String databaseName, String tableName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName)) {
            throw new BusinessException("partition.name.required");
        }
        String escapedDb = Chat2DBContext.getDbMetaData().getSQLIdentifierProcessor().escapeString(databaseName);
        String escapedTable = Chat2DBContext.getDbMetaData().getSQLIdentifierProcessor().escapeString(tableName);
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection,
                String.format(SQL_PARTITIONS, escapedDb, escapedTable), resultSet -> {
                    List<Map<String, Object>> partitions = new ArrayList<>();
                    while (resultSet.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("partitionName", resultSet.getString("PARTITION_NAME"));
                        row.put("subpartitionName", resultSet.getString("SUBPARTITION_NAME"));
                        row.put("ordinalPosition", resultSet.getLong("PARTITION_ORDINAL_POSITION"));
                        row.put("method", resultSet.getString("PARTITION_METHOD"));
                        row.put("subpartitionMethod", resultSet.getString("SUBPARTITION_METHOD"));
                        row.put("expression", resultSet.getString("PARTITION_EXPRESSION"));
                        row.put("description", resultSet.getString("PARTITION_DESCRIPTION"));
                        row.put("tableRows", resultSet.getLong("TABLE_ROWS"));
                        row.put("dataLength", resultSet.getLong("DATA_LENGTH"));
                        row.put("indexLength", resultSet.getLong("INDEX_LENGTH"));
                        row.put("comment", resultSet.getString("PARTITION_COMMENT"));
                        partitions.add(row);
                    }
                    return partitions;
                });
    }

    @Override
    public String truncatePartitionSql(String databaseName, String tableName, String partitionName) {
        requirePartition(databaseName, tableName, partitionName);
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " TRUNCATE PARTITION " + quote(partitionName);
    }

    @Override
    public String dropPartitionSql(String databaseName, String tableName, String partitionName) {
        requirePartition(databaseName, tableName, partitionName);
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " DROP PARTITION " + quote(partitionName);
    }

    @Override
    public String coalescePartitionSql(String databaseName, String tableName, int count) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName)) {
            throw new BusinessException("partition.name.required");
        }
        if (count < 1) {
            throw new BusinessException("partition.coalesceCountInvalid");
        }
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " COALESCE PARTITION " + count;
    }

    @Override
    public String maintainPartitionSql(String databaseName, String tableName, String operation, String partitionName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName) || StringUtils.isBlank(operation)) {
            throw new BusinessException("partition.name.required");
        }
        String op = operation.trim().toUpperCase(Locale.ROOT);
        if (!"ANALYZE".equals(op) && !"CHECK".equals(op) && !"OPTIMIZE".equals(op)) {
            throw new BusinessException("partition.operationUnsupported");
        }
        String target = StringUtils.isBlank(partitionName)
                ? "PARTITION ALL"
                : "PARTITION " + quote(partitionName);
        return op + " TABLE " + qualifiedTable(databaseName, tableName) + " " + target;
    }

    private static void requirePartition(String databaseName, String tableName, String partitionName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName) || StringUtils.isBlank(partitionName)) {
            throw new BusinessException("partition.name.required");
        }
    }

    private static String qualifiedTable(String databaseName, String tableName) {
        return Chat2DBContext.getDbMetaData().getMetaDataName(databaseName)
                + "." + Chat2DBContext.getDbMetaData().getMetaDataName(tableName);
    }

    private static String quote(String name) {
        return Chat2DBContext.getDbMetaData().getMetaDataName(name);
    }
}
