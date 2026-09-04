package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.metadata.TablePartition;
import ai.chat2db.community.domain.api.service.db.IDbPartitionService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IPartitionManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DbPartitionServiceImpl implements IDbPartitionService {
    @Override
    public List<TablePartition> list(String databaseName, String tableName) {
        return manager().list(databaseName, tableName);
    }

    @Override
    public String truncatePartitionSql(String databaseName, String tableName, String partitionName) {
        return manager().truncatePartitionSql(databaseName, tableName, partitionName);
    }

    @Override
    public String dropPartitionSql(String databaseName, String tableName, String partitionName) {
        return manager().dropPartitionSql(databaseName, tableName, partitionName);
    }

    @Override
    public String addPartitionSql(String databaseName, String tableName, String partitionName,
                                  String partitionDefinition, Integer count) {
        return manager().addPartitionSql(databaseName, tableName, partitionName, partitionDefinition, count);
    }

    @Override
    public String reorganizePartitionSql(String databaseName, String tableName, String partitionName,
                                         String partitionDefinitions) {
        return manager().reorganizePartitionSql(databaseName, tableName, partitionName, partitionDefinitions);
    }

    @Override
    public String coalescePartitionSql(String databaseName, String tableName, int count) {
        return manager().coalescePartitionSql(databaseName, tableName, count);
    }

    @Override
    public String maintainPartitionSql(String databaseName, String tableName, String operation,
                                       String partitionName) {
        return manager().maintainPartitionSql(databaseName, tableName, operation, partitionName);
    }

    private IPartitionManager manager() {
        IPartitionManager manager = Chat2DBContext.getPartitionManager();
        if (manager == null) {
            throw new BusinessException("partition.unsupported");
        }
        return manager;
    }
}
