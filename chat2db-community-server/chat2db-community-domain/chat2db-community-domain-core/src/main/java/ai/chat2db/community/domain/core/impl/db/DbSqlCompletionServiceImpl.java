package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.spi.DefaultSqlSyntaxHandler;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionResponse;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionKeywordCaseEnum;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlCompletionGetRequest;
import ai.chat2db.community.domain.api.service.db.IDbSqlCompletionService;
import ai.chat2db.community.domain.core.completion.SqlCompletionMetadataContext;
import ai.chat2db.community.domain.core.completion.SqlCompletionMetadataProviderAdapter;
import ai.chat2db.community.domain.core.converter.SqlCompletionConverter;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class DbSqlCompletionServiceImpl implements IDbSqlCompletionService {

    private final SqlCompletionConverter sqlCompletionConverter;
    private final GenericSqlCompletionEngine genericSqlCompletionService;

    @Override
    public SqlCompletionResponse complete(DbSqlCompletionGetRequest param) {
        if (Objects.isNull(param)) {
            return SqlCompletionResponse.rejected("sql.completion.param.null");
        }
        try {
            DBConfig dbConfig = Chat2DBContext.getDBConfig();
            if (Objects.isNull(dbConfig) || StringUtils.isBlank(dbConfig.getDbType())) {
                return SqlCompletionResponse.unsupported(null);
            }
            IDbMetaData metaData = Chat2DBContext.getDbMetaData();
            SqlCompletionMetadataProviderAdapter metadataProvider = new SqlCompletionMetadataProviderAdapter(
                    buildMetadataContext(param, dbConfig, metaData), sqlCompletionConverter);
            DbSqlCompletionRequest request = buildCompletionRequest(param, dbConfig, metadataProvider);
            if (StringUtils.equalsIgnoreCase(DatabaseTypeEnum.MYSQL.name(), dbConfig.getDbType())) {
                return DefaultSqlSyntaxHandler.complete(request);
            }
            SqlCompletionResponse response = genericSqlCompletionService.complete(param);
            response.setEditorHints(DefaultSqlSyntaxHandler.editorHints(request));
            return response;
        } catch (Exception e) { // impl-contract: fallback - completion failure returns a rejected completion response to the editor.
            log.warn("sql completion error", e);
            return SqlCompletionResponse.rejected("sql.completion.error");
        }
    }

    private DbSqlCompletionRequest buildCompletionRequest(DbSqlCompletionGetRequest param,
                                                          DBConfig dbConfig,
                                                          SqlCompletionMetadataProviderAdapter metadataProvider) {
        return DbSqlCompletionRequest.of(
                param.getSql(),
                resolveCursor(param),
                dbConfig.getDbType(),
                resolveMinPrefixLength(param),
                metadataProvider,
                SqlCompletionKeywordCaseEnum.from(param.getKeywordCase()).name(),
                param.getActiveSnippetSlot());
    }

    private SqlCompletionMetadataContext buildMetadataContext(DbSqlCompletionGetRequest param,
                                                              DBConfig dbConfig,
                                                              IDbMetaData metaData) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return SqlCompletionMetadataContext.builder()
                .dataSourceId(param.getDataSourceId())
                .databaseName(param.getDatabaseName())
                .schemaName(param.getSchemaName())
                .datasourceName(connectInfo == null ? null : connectInfo.getAlias())
                .dbConfig(dbConfig)
                .metaData(metaData)
                .connectionSupplier(Chat2DBContext::getConnection)
                .identifierProcessor(metaData == null ? null : metaData.getSQLIdentifierProcessor())
                .build();
    }

    private int resolveCursor(DbSqlCompletionGetRequest param) {
        if (param.getCursor() != null) {
            return param.getCursor();
        }
        return param.getSql() == null ? 0 : param.getSql().length();
    }

    private int resolveMinPrefixLength(DbSqlCompletionGetRequest param) {
        return param.getMinPrefixLength() == null ? 0 : param.getMinPrefixLength();
    }
}
