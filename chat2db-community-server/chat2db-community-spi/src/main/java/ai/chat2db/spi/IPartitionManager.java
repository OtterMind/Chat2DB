package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.metadata.TablePartition;

import java.util.List;

/** Database-plugin capability for partition metadata and maintenance SQL. */
public interface IPartitionManager {
    List<TablePartition> list(String databaseName, String tableName);
    String truncatePartitionSql(String databaseName, String tableName, String partitionName);
    String dropPartitionSql(String databaseName, String tableName, String partitionName);
    String addPartitionSql(String databaseName, String tableName, String partitionName,
                           String partitionDefinition, Integer count);
    String reorganizePartitionSql(String databaseName, String tableName, String partitionName,
                                  String partitionDefinitions);
    String coalescePartitionSql(String databaseName, String tableName, int count);
    String maintainPartitionSql(String databaseName, String tableName, String operation, String partitionName);
}
