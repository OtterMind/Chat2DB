package ai.chat2db.server.domain.api.service;

import ai.chat2db.server.domain.api.param.CreateVirtualFKParam;
import ai.chat2db.server.domain.api.param.UpdateVirtualFKParam;
import ai.chat2db.spi.model.ForeignKey;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.model.VirtualForeignKey;

import java.util.List;

public interface ForeignKeySyncService {

    SyncResult syncForeignKeys(Long dataSourceId, String databaseName, String schemaName, String tableName);

    List<ForeignKey> listAllForeignKeys(Long dataSourceId, String databaseName, String schemaName, String tableName);

    VirtualForeignKey createVirtualFK(CreateVirtualFKParam param);

    List<VirtualForeignKey> createInferredVirtualFKs(List<CreateVirtualFKParam> params);

    VirtualForeignKey updateVirtualFK(UpdateVirtualFKParam param);

    void deleteVirtualFK(Long id);

    String deleteRealFK(Long id);

    List<String> generateForeignKeyDDL(Table oldTable, Table newTable);

    List<ForeignKey> queryRealForeignKeys(Long dataSourceId, String databaseName, String schemaName, String tableName);

    List<VirtualForeignKey> queryVirtualForeignKeys(Long dataSourceId, String databaseName, String schemaName, String tableName);

    List<VirtualForeignKey> queryAllVirtualForeignKeys(Long dataSourceId, String databaseName, String schemaName);

    int cleanInvalidVirtualForeignKeys(Long dataSourceId, String databaseName, String schemaName, List<String> existingTableNames);

    class SyncResult {
        private int added;
        private int deleted;
        private int unchanged;

        public SyncResult(int added, int deleted, int unchanged) {
            this.added = added;
            this.deleted = deleted;
            this.unchanged = unchanged;
        }

        public int getAdded() { return added; }
        public int getDeleted() { return deleted; }
        public int getUnchanged() { return unchanged; }
    }
}
