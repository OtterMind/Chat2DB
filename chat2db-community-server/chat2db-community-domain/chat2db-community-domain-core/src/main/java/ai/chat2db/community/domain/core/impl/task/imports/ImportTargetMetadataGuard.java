package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.List;

public final class ImportTargetMetadataGuard {

    private ImportTargetMetadataGuard() {
    }

    public static TableMetadataRequest resolve(IDbMetaData metaData, Connection connection, ConnectInfo connectInfo,
            TaskTargetSnapshot target) {
        if (target == null) {
            throw new BusinessException("import.target.required");
        }
        return resolve(metaData, connection, connectInfo, target.getDataSourceId(), target.getDatabaseName(),
                target.getSchemaName(), target.getTableName());
    }

    public static TableMetadataRequest resolve(IDbMetaData metaData, Connection connection, ConnectInfo connectInfo,
            Long requestDataSourceId, String requestDatabaseName, String requestSchemaName, String requestTableName) {
        if (metaData == null || connection == null || connectInfo == null) {
            throw new BusinessException("import.target.contextRequired");
        }
        if (requestDataSourceId == null || connectInfo.getDataSourceId() == null
                || !requestDataSourceId.equals(connectInfo.getDataSourceId())) {
            throw new BusinessException("import.target.contextMismatch", new Object[] {"datasource"});
        }
        String trustedDatabaseName = StringUtils.trimToNull(connectInfo.getDatabaseName());
        String trustedSchemaName = StringUtils.trimToNull(connectInfo.getSchemaName());
        rejectMismatch("database", requestDatabaseName, trustedDatabaseName);
        rejectMismatch("schema", requestSchemaName, trustedSchemaName);
        String requestedTableName = safeTableName(requestTableName);
        String resolvedTableName = resolveExistingTableName(metaData, connection, trustedDatabaseName,
                trustedSchemaName, requestedTableName);
        return new TableMetadataRequest(trustedDatabaseName, trustedSchemaName, resolvedTableName);
    }

    public static List<TableColumn> exactTableColumns(IDbMetaData metaData, Connection connection,
            TableMetadataRequest request) {
        List<TableColumn> columns = metaData.columns(connection, request);
        if (CollectionUtils.isEmpty(columns)) {
            return columns;
        }
        return columns.stream()
                .filter(column -> StringUtils.isBlank(column.getTableName())
                        || StringUtils.equalsIgnoreCase(column.getTableName(), request.getTableName()))
                .toList();
    }

    private static void rejectMismatch(String label, String requested, String trusted) {
        String requestedName = StringUtils.trimToNull(requested);
        if (requestedName == null) {
            return;
        }
        if (trusted == null || !StringUtils.equals(requestedName, trusted)) {
            throw new BusinessException("import.target.contextMismatch", new Object[] {label});
        }
    }

    private static String safeTableName(String tableName) {
        String name = StringUtils.trimToNull(tableName);
        if (name == null) {
            throw new BusinessException("import.target.tableRequired");
        }
        if (!StringUtils.equals(name, tableName) || containsUnsafeMetadataCharacter(name)) {
            throw new BusinessException("import.target.unsafeTableName");
        }
        return name;
    }

    private static boolean containsUnsafeMetadataCharacter(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || c == '%' || c == '*' || c == '?' || c == '.'
                    || c == '/' || c == '\\' || c == '\'' || c == '"' || c == '`'
                    || c == '[' || c == ']' || c == ';') {
                return true;
            }
        }
        return false;
    }

    private static String resolveExistingTableName(IDbMetaData metaData, Connection connection, String databaseName,
            String schemaName, String requestedTableName) {
        List<Table> tables = metaData.tables(connection, new TablesRequest(databaseName, schemaName, null));
        if (CollectionUtils.isEmpty(tables)) {
            throw new BusinessException("import.target.tableMissing");
        }
        return tables.stream()
                .map(Table::getName)
                .filter(StringUtils::isNotBlank)
                .filter(name -> StringUtils.equalsIgnoreCase(name, requestedTableName))
                .findFirst()
                .orElseThrow(() -> new BusinessException("import.target.tableMissing"));
    }
}
