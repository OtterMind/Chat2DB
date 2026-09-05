package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.community.domain.api.model.metadata.extension.MetadataAccessContext;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

final class TablespaceMetadataAccessFilter {

    private final MetadataAccessPolicyManager policyManager;

    TablespaceMetadataAccessFilter(MetadataAccessPolicyManager policyManager) {
        this.policyManager = policyManager;
    }

    List<Tablespace> filter(Long dataSourceId, String dbType, List<Tablespace> tablespaces) {
        if (policyManager.isEmpty() || tablespaces == null) {
            return tablespaces;
        }
        return tablespaces.stream()
                .map(tablespace -> filter(dataSourceId, dbType, tablespace))
                .toList();
    }

    Tablespace filter(Long dataSourceId, String dbType, Tablespace tablespace) {
        if (policyManager.isEmpty() || tablespace == null) {
            return tablespace;
        }
        return Tablespace.builder()
                .name(tablespace.getName())
                .engine(tablespace.getEngine())
                .spaceId(tablespace.getSpaceId())
                .fileBlockSize(tablespace.getFileBlockSize())
                .autoextendSize(tablespace.getAutoextendSize())
                .maxSize(tablespace.getMaxSize())
                .extentSize(tablespace.getExtentSize())
                .initialSize(tablespace.getInitialSize())
                .status(tablespace.getStatus())
                .comment(tablespace.getComment())
                .occupyingTables(filterOccupyingTables(dataSourceId, dbType, tablespace.getOccupyingTables()))
                .build();
    }

    List<String> filterOccupyingTables(Long dataSourceId, String dbType, List<String> occupyingTables) {
        if (policyManager.isEmpty() || occupyingTables == null) {
            return occupyingTables;
        }
        return policyManager.filter(occupyingTables,
                qualifiedName -> resource(dataSourceId, dbType, qualifiedName));
    }

    private MetadataAccessContext resource(Long dataSourceId, String dbType, String qualifiedName) {
        String databaseName = null;
        String tableName = qualifiedName;
        int separator = StringUtils.defaultString(qualifiedName).indexOf('.');
        if (separator > 0) {
            databaseName = qualifiedName.substring(0, separator);
            tableName = qualifiedName.substring(separator + 1);
        }
        return MetadataAccessContext.builder()
                .dataSourceId(dataSourceId)
                .dbType(dbType)
                .databaseName(databaseName)
                .tableName(tableName)
                .operationType("SELECT")
                .build();
    }
}
