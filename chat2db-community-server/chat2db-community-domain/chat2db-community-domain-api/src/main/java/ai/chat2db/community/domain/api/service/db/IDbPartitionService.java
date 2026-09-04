package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.metadata.TablePartition;

import java.util.List;

/**
 * MySQL table partition inspection and maintenance (MYSQL-OBJ-009). Partition definitions
 * and statistics come from information_schema.PARTITIONS; maintenance statements are
 * generated per partition type (ADD for RANGE/LIST/HASH/KEY, DROP/TRUNCATE/REORGANIZE
 * for RANGE/LIST, COALESCE for HASH/KEY, ANALYZE/CHECK/OPTIMIZE for all) and previewed
 * before execution.
 */
public interface IDbPartitionService {

    /**
     * Lists partitions of a table with method, expression, boundary, and statistics.
     *
     * @param databaseName the database name.
     * @param tableName    the table name.
     * @return partition metadata ordered by ordinal position.
     */
    List<TablePartition> list(String databaseName, String tableName);

    /**
     * Generates the TRUNCATE PARTITION statement.
     *
     * @param databaseName the database name.
     * @param tableName    the table name.
     * @param partitionName the partition name.
     * @return the TRUNCATE PARTITION SQL.
     */
    String truncatePartitionSql(String databaseName, String tableName, String partitionName);

    /**
     * Generates the DROP PARTITION statement (RANGE/LIST partitions only).
     *
     * @param databaseName the database name.
     * @param tableName    the table name.
     * @param partitionName the partition name.
     * @return the DROP PARTITION SQL.
     */
    String dropPartitionSql(String databaseName, String tableName, String partitionName);

    /**
     * Generates the ADD PARTITION statement for RANGE/LIST/HASH/KEY partitioned tables.
     *
     * @param databaseName        the database name.
     * @param tableName           the table name.
     * @param partitionName       the partition name for RANGE/LIST.
     * @param partitionDefinition the VALUES clause for RANGE/LIST.
     * @param count               the partition count to add for HASH/KEY.
     * @return the ADD PARTITION SQL.
     */
    String addPartitionSql(String databaseName, String tableName, String partitionName,
            String partitionDefinition, Integer count);

    /**
     * Generates the REORGANIZE PARTITION statement for RANGE/LIST partitioned tables.
     *
     * @param databaseName         the database name.
     * @param tableName            the table name.
     * @param partitionName        the existing partition to reorganize.
     * @param partitionDefinitions replacement partition definitions.
     * @return the REORGANIZE PARTITION SQL.
     */
    String reorganizePartitionSql(String databaseName, String tableName, String partitionName,
            String partitionDefinitions);

    /**
     * Generates the COALESCE PARTITION statement (HASH/KEY partitions only).
     *
     * @param databaseName the database name.
     * @param tableName    the table name.
     * @param count        the number of partitions to remove.
     * @return the COALESCE PARTITION SQL.
     */
    String coalescePartitionSql(String databaseName, String tableName, int count);

    /**
     * Generates an ANALYZE/CHECK/OPTIMIZE PARTITION statement.
     *
     * @param databaseName  the database name.
     * @param tableName     the table name.
     * @param operation     ANALYZE, CHECK, or OPTIMIZE.
     * @param partitionName the partition name (null for all partitions).
     * @return the maintenance SQL.
     */
    String maintainPartitionSql(String databaseName, String tableName, String operation, String partitionName);
}
