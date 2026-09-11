package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

final class TrustedMetadataRequestResolver {

    private TrustedMetadataRequestResolver() {
    }

    static TableMetadataRequest table(Long requestDataSourceId, String requestDatabaseName,
            String requestSchemaName, String requestTableName) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            throw new BusinessException("connection.error");
        }
        if (requestDataSourceId == null || !Objects.equals(requestDataSourceId, connectInfo.getDataSourceId())) {
            throw new BusinessException("common.permissionDenied");
        }

        String trustedDatabaseName = StringUtils.trimToNull(connectInfo.getDatabaseName());
        String trustedSchemaName = StringUtils.trimToNull(connectInfo.getSchemaName());
        requireMatchesTrusted(requestDatabaseName, trustedDatabaseName);
        requireMatchesTrusted(requestSchemaName, trustedSchemaName);

        String requestedTableName = normalizeIdentifier(requestTableName);
        if (requestedTableName == null) {
            throw new BusinessException("common.paramError");
        }
        String trustedTableName = resolveTableName(trustedDatabaseName, trustedSchemaName, requestedTableName);
        return new TableMetadataRequest(trustedDatabaseName, trustedSchemaName, trustedTableName);
    }

    private static String resolveTableName(String databaseName, String schemaName, String requestedTableName) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        Connection connection = Chat2DBContext.getConnection();
        List<String> matchingNames = metaData.tables(connection, new TablesRequest(databaseName, schemaName, null))
                .stream()
                .filter(Objects::nonNull)
                .map(Table::getName)
                .filter(StringUtils::isNotBlank)
                .filter(name -> StringUtils.equalsIgnoreCase(name, requestedTableName))
                .toList();
        return matchingNames.stream()
                .filter(name -> StringUtils.equals(name, requestedTableName))
                .findFirst()
                .orElseGet(() -> matchingNames.size() == 1 ? matchingNames.get(0) : missingTable());
    }

    private static String missingTable() {
        throw new BusinessException("common.paramError");
    }

    private static void requireMatchesTrusted(String requestValue, String trustedValue) {
        String normalizedRequest = normalizeIdentifier(requestValue);
        if (StringUtils.isBlank(normalizedRequest)) {
            return;
        }
        if (!Objects.equals(normalizedRequest, trustedValue)) {
            throw new BusinessException("common.permissionDenied");
        }
    }

    private static String normalizeIdentifier(String identifier) {
        ISQLIdentifierProcessor identifierProcessor = Chat2DBContext.getDbMetaData().getSQLIdentifierProcessor();
        return identifierProcessor.removeIdentifierQuote(StringUtils.trimToNull(identifier));
    }
}
