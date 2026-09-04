package ai.chat2db.plugin.mysql.partition;

import ai.chat2db.community.domain.api.model.metadata.TablePartition;
import ai.chat2db.spi.IPartitionManager;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MySQL partition inspection and maintenance (MYSQL-OBJ-009). Works on 5.7/8.0 across
 * RANGE/RANGE COLUMNS/LIST/LIST COLUMNS/HASH/LINEAR HASH/KEY/LINEAR KEY.
 */
public class MysqlPartitionManager implements IPartitionManager {

    private static final String SQL_PARTITIONS =
            "SELECT PARTITION_NAME, SUBPARTITION_NAME, PARTITION_ORDINAL_POSITION, "
                    + "SUBPARTITION_ORDINAL_POSITION, "
                    + "PARTITION_METHOD, SUBPARTITION_METHOD, PARTITION_EXPRESSION, "
                    + "SUBPARTITION_EXPRESSION, PARTITION_DESCRIPTION, TABLE_ROWS, "
                    + "AVG_ROW_LENGTH, DATA_LENGTH, MAX_DATA_LENGTH, INDEX_LENGTH, DATA_FREE, "
                    + "CREATE_TIME, UPDATE_TIME, CHECK_TIME, CHECKSUM, PARTITION_COMMENT, "
                    + "NODEGROUP, TABLESPACE_NAME "
                    + "FROM information_schema.PARTITIONS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                    + "ORDER BY PARTITION_ORDINAL_POSITION, SUBPARTITION_ORDINAL_POSITION";

    private static final String TABLE_NOT_PARTITIONED = "table.notPartitioned";
    private static final Set<String> RANGE_LIST_METHODS = Set.of("RANGE", "RANGE COLUMNS", "LIST", "LIST COLUMNS");
    private static final Set<String> HASH_KEY_METHODS = Set.of("HASH", "LINEAR HASH", "KEY", "LINEAR KEY");

    @Override
    public List<TablePartition> list(String databaseName, String tableName) {
        requireSupportedMysql();
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName)) {
            throw new BusinessException("partition.name.required");
        }
        Connection connection = Chat2DBContext.getConnection();
        try (PreparedStatement statement = connection.prepareStatement(SQL_PARTITIONS)) {
            statement.setString(1, databaseName);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TablePartition> partitions = new ArrayList<>();
                while (resultSet.next()) {
                    String partitionName = resultSet.getString("PARTITION_NAME");
                    String subpartitionName = resultSet.getString("SUBPARTITION_NAME");
                    if (StringUtils.isBlank(partitionName) && StringUtils.isBlank(subpartitionName)) {
                        continue;
                    }
                    TablePartition partition = new TablePartition();
                    partition.setPartitionName(partitionName);
                    partition.setSubpartitionName(subpartitionName);
                    partition.setOrdinalPosition(nullableLong(resultSet, "PARTITION_ORDINAL_POSITION"));
                    partition.setSubpartitionOrdinalPosition(nullableLong(resultSet, "SUBPARTITION_ORDINAL_POSITION"));
                    partition.setMethod(resultSet.getString("PARTITION_METHOD"));
                    partition.setSubpartitionMethod(resultSet.getString("SUBPARTITION_METHOD"));
                    partition.setExpression(resultSet.getString("PARTITION_EXPRESSION"));
                    partition.setSubpartitionExpression(resultSet.getString("SUBPARTITION_EXPRESSION"));
                    partition.setDescription(resultSet.getString("PARTITION_DESCRIPTION"));
                    partition.setTableRows(nullableLong(resultSet, "TABLE_ROWS"));
                    partition.setAvgRowLength(nullableLong(resultSet, "AVG_ROW_LENGTH"));
                    partition.setDataLength(nullableLong(resultSet, "DATA_LENGTH"));
                    partition.setMaxDataLength(nullableLong(resultSet, "MAX_DATA_LENGTH"));
                    partition.setIndexLength(nullableLong(resultSet, "INDEX_LENGTH"));
                    partition.setDataFree(nullableLong(resultSet, "DATA_FREE"));
                    partition.setCreateTime(resultSet.getString("CREATE_TIME"));
                    partition.setUpdateTime(resultSet.getString("UPDATE_TIME"));
                    partition.setCheckTime(resultSet.getString("CHECK_TIME"));
                    partition.setChecksum(nullableLong(resultSet, "CHECKSUM"));
                    partition.setComment(resultSet.getString("PARTITION_COMMENT"));
                    partition.setNodegroup(resultSet.getString("NODEGROUP"));
                    partition.setTablespaceName(resultSet.getString("TABLESPACE_NAME"));
                    partitions.add(partition);
                }
                return partitions;
            }
        } catch (SQLException exception) {
            throw new BusinessException("partition.listFailed", new Object[]{exception.getMessage()}, exception);
        }
    }

    @Override
    public String truncatePartitionSql(String databaseName, String tableName, String partitionName) {
        requireSupportedMysql();
        requirePartition(databaseName, tableName, partitionName);
        requireRangeListPartition(databaseName, tableName, partitionName);
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " TRUNCATE PARTITION " + quote(partitionName);
    }

    @Override
    public String dropPartitionSql(String databaseName, String tableName, String partitionName) {
        requireSupportedMysql();
        requirePartition(databaseName, tableName, partitionName);
        requireRangeListPartition(databaseName, tableName, partitionName);
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " DROP PARTITION " + quote(partitionName);
    }

    @Override
    public String addPartitionSql(String databaseName, String tableName, String partitionName,
            String partitionDefinition, Integer count) {
        requireSupportedMysql();
        PartitionedTable table = requirePartitionedTable(databaseName, tableName);
        String method = table.method;
        if (HASH_KEY_METHODS.contains(method)) {
            requirePositiveCount(count);
            return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                    + " ADD PARTITION PARTITIONS " + count;
        }
        if (!RANGE_LIST_METHODS.contains(method)) {
            throw new BusinessException("partition.typeUnsupported");
        }
        if (method.contains("RANGE") && table.hasMaxValue) {
            throw new BusinessException("partition.addRequiresReorganize");
        }
        if (StringUtils.isBlank(partitionName) || StringUtils.isBlank(partitionDefinition)) {
            throw new BusinessException("partition.name.required");
        }
        String definition = sanitizePartitionDefinition(partitionDefinition);
        requireAddDefinitionMatchesMethod(method, definition);
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " ADD PARTITION (PARTITION " + quote(partitionName) + " " + definition + ")";
    }

    @Override
    public String reorganizePartitionSql(String databaseName, String tableName, String partitionName,
            String partitionDefinitions) {
        requireSupportedMysql();
        requirePartition(databaseName, tableName, partitionName);
        requireRangeListPartition(databaseName, tableName, partitionName);
        String definitions = sanitizePartitionDefinition(partitionDefinitions);
        if (!definitions.toUpperCase(Locale.ROOT).contains("PARTITION ")) {
            throw new BusinessException("partition.definitionInvalid");
        }
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " REORGANIZE PARTITION " + quote(partitionName) + " INTO (" + definitions + ")";
    }

    @Override
    public String coalescePartitionSql(String databaseName, String tableName, int count) {
        requireSupportedMysql();
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName)) {
            throw new BusinessException("partition.name.required");
        }
        requirePositiveCount(count);
        String method = requirePartitionedTable(databaseName, tableName).method;
        if (!HASH_KEY_METHODS.contains(method)) {
            throw new BusinessException("partition.typeUnsupported");
        }
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " COALESCE PARTITION " + count;
    }

    @Override
    public String maintainPartitionSql(String databaseName, String tableName, String operation, String partitionName) {
        requireSupportedMysql();
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName) || StringUtils.isBlank(operation)) {
            throw new BusinessException("partition.name.required");
        }
        String op = operation.trim().toUpperCase(Locale.ROOT);
        if (!"ANALYZE".equals(op) && !"CHECK".equals(op) && !"OPTIMIZE".equals(op)) {
            throw new BusinessException("partition.operationUnsupported");
        }
        requirePartitionedTable(databaseName, tableName);
        String target = StringUtils.isBlank(partitionName)
                ? "PARTITION ALL"
                : "PARTITION " + quote(partitionName);
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName) + " " + op + " " + target;
    }

    private PartitionedTable requirePartitionedTable(String databaseName, String tableName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName)) {
            throw new BusinessException("partition.name.required");
        }
        List<TablePartition> rows = list(databaseName, tableName);
        if (rows.isEmpty()) {
            throw new BusinessException(TABLE_NOT_PARTITIONED);
        }
        String method = StringUtils.upperCase(rows.get(0).getMethod(), Locale.ROOT);
        if (!RANGE_LIST_METHODS.contains(method) && !HASH_KEY_METHODS.contains(method)) {
            throw new BusinessException("partition.typeUnsupported");
        }
        Set<String> partitionNames = new HashSet<>();
        boolean hasMaxValue = false;
        for (TablePartition row : rows) {
            String name = row.getPartitionName();
            if (StringUtils.isNotBlank(name)) {
                partitionNames.add(name);
            }
            if (StringUtils.containsIgnoreCase(row.getDescription(), "MAXVALUE")) {
                hasMaxValue = true;
            }
        }
        return new PartitionedTable(method, partitionNames, hasMaxValue);
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private void requireRangeListPartition(String databaseName, String tableName, String partitionName) {
        PartitionedTable table = requirePartitionedTable(databaseName, tableName);
        if (!RANGE_LIST_METHODS.contains(table.method)) {
            throw new BusinessException("partition.typeUnsupported");
        }
        if (!table.partitionNames.contains(partitionName)) {
            throw new BusinessException("partition.name.required");
        }
    }

    private static void requirePositiveCount(Integer count) {
        if (count == null || count < 1) {
            throw new BusinessException("partition.coalesceCountInvalid");
        }
    }

    private static void requireAddDefinitionMatchesMethod(String method, String definition) {
        String normalized = definition.toUpperCase(Locale.ROOT);
        if (method.contains("RANGE") && !normalized.startsWith("VALUES LESS THAN ")) {
            throw new BusinessException("partition.definitionInvalid");
        }
        if (method.contains("LIST") && !normalized.startsWith("VALUES IN ")) {
            throw new BusinessException("partition.definitionInvalid");
        }
    }

    private static String sanitizePartitionDefinition(String definition) {
        String trimmed = StringUtils.trimToEmpty(definition);
        if (StringUtils.isBlank(trimmed) || trimmed.contains(";") || trimmed.indexOf('\0') >= 0) {
            throw new BusinessException("partition.definitionInvalid");
        }
        return trimmed;
    }

    private static void requirePartition(String databaseName, String tableName, String partitionName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName) || StringUtils.isBlank(partitionName)) {
            throw new BusinessException("partition.name.required");
        }
    }

    private static void requireSupportedMysql() {
        String dbType = Chat2DBContext.getConnectInfo() == null ? null : Chat2DBContext.getConnectInfo().getDbType();
        if (!DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(dbType) || !isAtLeastMysql57(Chat2DBContext.getDbVersion())) {
            throw new BusinessException("partition.unsupported");
        }
    }

    private static boolean isAtLeastMysql57(String version) {
        if (StringUtils.isBlank(version)) {
            return false;
        }
        String[] parts = version.replaceFirst("^[^0-9]*", "").split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 5 || major == 5 && minor >= 7;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String qualifiedTable(String databaseName, String tableName) {
        return Chat2DBContext.getDbMetaData().getMetaDataName(databaseName)
                + "." + Chat2DBContext.getDbMetaData().getMetaDataName(tableName);
    }

    private static String quote(String name) {
        return Chat2DBContext.getDbMetaData().getMetaDataName(name);
    }

    private record PartitionedTable(String method, Set<String> partitionNames, boolean hasMaxValue) {
    }
}
