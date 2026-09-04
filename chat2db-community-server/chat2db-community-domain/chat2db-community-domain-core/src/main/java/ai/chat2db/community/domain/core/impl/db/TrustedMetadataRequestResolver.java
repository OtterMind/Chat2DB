package ai.chat2db.community.domain.core.impl.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.sql.Connection;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

final class TrustedMetadataRequestResolver {

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}\\p{N}_$][\\p{L}\\p{N}_$ -]*");

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

        QualifiedIdentifier identifier = parse(requestTableName);
        requireMatchesTrusted(identifier.databaseName(), trustedDatabaseName);
        requireMatchesTrusted(identifier.schemaName(), trustedSchemaName);
        String trustedTableName = resolveTableName(trustedDatabaseName, trustedSchemaName, identifier.tableName());
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

    private static QualifiedIdentifier parse(String tableName) {
        List<String> parts = split(StringUtils.trimToNull(tableName));
        if (parts.isEmpty()) {
            throw new BusinessException("common.paramError");
        }
        List<String> identifiers = parts.stream().map(TrustedMetadataRequestResolver::canonicalIdentifier).toList();
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        boolean supportSchema = dbConfig == null || dbConfig.isSupportSchema();
        boolean supportDatabase = dbConfig == null || dbConfig.isSupportDatabase();
        String databaseName = null;
        String schemaName = null;
        if (identifiers.size() > 1) {
            if (supportSchema) {
                schemaName = identifiers.get(identifiers.size() - 2);
            }
            if (supportDatabase && !supportSchema) {
                databaseName = identifiers.get(identifiers.size() - 2);
            }
            if (supportDatabase && supportSchema && identifiers.size() > 2) {
                databaseName = identifiers.get(identifiers.size() - 3);
                schemaName = identifiers.get(identifiers.size() - 2);
            }
        }
        return new QualifiedIdentifier(databaseName, schemaName, identifiers.get(identifiers.size() - 1));
    }

    private static List<String> split(String value) {
        if (StringUtils.isBlank(value)) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        char quote = 0;
        char quoteEnd = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote == 0 && c == '.') {
                parts.add(part.toString());
                part.setLength(0);
                continue;
            }
            if (quote == 0 && isQuoteStart(c)) {
                quote = c;
                quoteEnd = c == '[' ? ']' : c;
            } else if (quote != 0 && c == quoteEnd) {
                quote = 0;
                quoteEnd = 0;
            }
            part.append(c);
        }
        if (quote != 0) {
            throw new BusinessException("common.paramError");
        }
        parts.add(part.toString());
        return parts;
    }

    private static String canonicalIdentifier(String value) {
        String identifier = stripIdentifierQuote(StringUtils.trimToNull(value));
        if (StringUtils.isBlank(identifier) || !IDENTIFIER.matcher(identifier).matches()) {
            throw new BusinessException("common.paramError");
        }
        return identifier;
    }

    private static String stripIdentifierQuote(String identifier) {
        if (identifier == null || identifier.length() < 2) {
            return identifier;
        }
        if ((identifier.startsWith("\"") && identifier.endsWith("\""))
                || (identifier.startsWith("`") && identifier.endsWith("`"))
                || (identifier.startsWith("'") && identifier.endsWith("'"))
                || (identifier.startsWith("[") && identifier.endsWith("]"))) {
            return identifier.substring(1, identifier.length() - 1);
        }
        return identifier;
    }

    private static boolean isQuoteStart(char c) {
        return c == '"' || c == '`' || c == '\'' || c == '[';
    }

    private static void requireMatchesTrusted(String requestValue, String trustedValue) {
        String normalizedRequest = stripIdentifierQuote(StringUtils.trimToNull(requestValue));
        if (StringUtils.isBlank(normalizedRequest)) {
            return;
        }
        if (!Objects.equals(normalizedRequest, trustedValue)) {
            throw new BusinessException("common.permissionDenied");
        }
    }

    private record QualifiedIdentifier(String databaseName, String schemaName, String tableName) {
    }
}
