package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.request.db.*;
import ai.chat2db.community.domain.api.model.request.db.DbCopyInValuesRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDatabaseDeletePrepareRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDatabaseObjectDeleteExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDmlExportRequest;
import ai.chat2db.community.domain.api.model.request.db.DbObjectDropRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableCopyRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableShowCreateRequest;
import ai.chat2db.community.domain.api.model.request.db.TableSelector;
import ai.chat2db.community.domain.api.model.request.pin.DbTablePinRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlCompletionGetRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlContextParserRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlFormatRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlHoverRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlKeywordRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlValidSelectRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableVectorRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSelectResultUpdateRequest;
import ai.chat2db.community.web.api.model.response.data.source.DatabaseResponse;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.request.db.*;
import ai.chat2db.community.web.api.model.response.db.*;
import ai.chat2db.community.web.api.model.request.http.TableSchemaRequest;
import ai.chat2db.community.web.api.model.request.mcp.McpExecuteSqlRequest;
import ai.chat2db.community.web.api.model.request.sql.SqlFormatRequest;
import ai.chat2db.community.web.api.model.request.sql.SqlValidSelectRequest;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogSourceEnum;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.db.DatabaseObjectDeletePrepare;
import ai.chat2db.community.domain.api.model.sql.Sql;
import ai.chat2db.community.domain.api.model.sql.SqlPreview;
import ai.chat2db.community.domain.api.model.view.*;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.Named;

import java.util.ArrayList;
import java.util.List;


@Mapper(componentModel = "spring")
public abstract class DbWebConverter {


    @Mappings({
            @Mapping(target = "tableName", ignore = true),
            @Mapping(target = "pageSizeAll", ignore = true)
    })
    public abstract DbDlExecuteRequest request2param(SqlEditorExecuteRequest request);

    @Mappings({
            @Mapping(target = "consoleId", ignore = true),
            @Mapping(target = "applyId", ignore = true),
            @Mapping(target = "sql", ignore = true),
            @Mapping(target = "pageSizeAll", ignore = true),
            @Mapping(target = "single", ignore = true),
            @Mapping(target = "resultSetId", ignore = true),
            @Mapping(target = "errorContinue", ignore = true),
            @Mapping(target = "explain", ignore = true)
    })
    public abstract DbDlExecuteRequest request2param(TableBrowseRequest request);

    @Mappings({
            @Mapping(target = "consoleId", ignore = true),
            @Mapping(target = "applyId", ignore = true),
            @Mapping(target = "tableName", ignore = true),
            @Mapping(target = "pageNo", ignore = true),
            @Mapping(target = "pageSize", ignore = true),
            @Mapping(target = "pageSizeAll", ignore = true),
            @Mapping(target = "single", ignore = true),
            @Mapping(target = "resultSetId", ignore = true),
            @Mapping(target = "errorContinue", ignore = true),
            @Mapping(target = "explain", ignore = true)
    })
    public abstract DbDlExecuteRequest request2param(TableEditExecuteRequest request);

    @Mappings({
            @Mapping(target = "applyId", ignore = true),
            @Mapping(target = "pageNo", ignore = true),
            @Mapping(target = "pageSize", ignore = true),
            @Mapping(target = "pageSizeAll", ignore = true),
            @Mapping(target = "single", ignore = true),
            @Mapping(target = "resultSetId", ignore = true),
            @Mapping(target = "errorContinue", ignore = true),
            @Mapping(target = "explain", ignore = true)
    })
    public abstract DbDlExecuteRequest request2param(DdlExecuteRequest request);


    @Mapping(target = "applyId", ignore = true)
    public abstract DbDlExecuteRequest tableManageRequest2param(DdlRequest request);


    public abstract DbDlCountRequest request2param(DdlCountRequest request);


    public abstract DbTableQueryRequest tableRequest2param(TableDetailQueryRequest request);


    public abstract Table tableRequest2param(TableRequest request);


    public abstract SqlResponse dto2response(Sql dto);


    public abstract DbTablePageQueryRequest tablePageRequest2param(TableBriefQueryRequest request);

    public DbTablePageQueryRequest tablePageRequest2param(TableBriefQueryRequest request, Long userId) {
        DbTablePageQueryRequest param = tablePageRequest2param(request);
        param.setUserId(userId);
        return param;
    }


    public abstract DbTableQueryRequest tableRequest2param(TableDeleteRequest request);


    public abstract DbTableShowCreateRequest ddlExport2showCreate(DdlExportRequest request);


    public abstract DbObjectDropRequest tableDelete2dropParam(TableDeleteRequest request);


    @Named("fullExecuteResult")
    public abstract ExecuteResultResponse dto2response(ExecuteResponse dto);

    @Mapping(target = "dataList", ignore = true)
    public abstract ExecuteResultResponse dto2completionResponse(ExecuteResponse dto);

    public abstract ExecuteResponse response2dto(ExecuteResultResponse vo);

    public abstract SqlPreviewResponse dto2response(SqlPreview dto);

    public abstract RoutineOperation request2param(RoutineOperationRequest request);

    public abstract RoutineOperation request2param(RoutineMigrationRequest request);


    @IterableMapping(qualifiedByName = "fullExecuteResult")
    public abstract List<ExecuteResultResponse> dto2response(List<ExecuteResponse> dtos);


    public abstract ColumnResponse columnDto2response(TableColumn dto);


    public abstract List<ColumnResponse> columnDto2response(List<TableColumn> dtos);


    @Mappings({
            @Mapping(source = "columnList", target = "columnList")
    })
    public abstract IndexResponse indexDto2response(TableIndex dto);


    public abstract List<IndexResponse> indexDto2response(List<TableIndex> dtos);


    @Mappings({
            @Mapping(source = "columnList", target = "columnList"),
            @Mapping(source = "indexList", target = "indexList"),
    })
    public abstract TableResponse tableDto2response(Table dto);


    public abstract List<TableResponse> tableDto2response(List<Table> dtos);


    public abstract List<SchemaResponse> schemaDto2response(List<Schema> tableColumns);


    public abstract SchemaResponse schemaDto2response(Schema dto);

    @Mappings({
            @Mapping(source = "databaseName", target = "dataBaseName"),
            @Mapping(target = "connection", ignore = true)
    })
    public abstract DbSchemaQueryRequest dataSourceRequest2schemaQuery(DataSourceBaseRequest request);

    public abstract DbMetaDataQueryRequest dataSourceRequest2metadataQuery(DataSourceBaseRequest request);

    @Mappings({
            @Mapping(source = "schemaName", target = "name"),
            @Mapping(target = "system", ignore = true)
    })
    public abstract Schema request2param(SchemaCreateRequest request);

    public abstract DbSchemaOperationRequest request2param(UpdateSchemaRequest request);


    public abstract DatabaseResponse databaseDto2response(Database dto);


    public abstract List<DatabaseResponse> databaseDto2response(List<Database> dto);

    public abstract MetaSchemaResponse metaSchemaDto2response(MetaSchema data);


    public abstract DbSelectResultUpdateRequest request2param(SelectResultUpdateRequest request);

    public abstract DbCopyInValuesRequest request2param(CopyInValuesRequest request);

    public DbDmlExecutionRequest sqlEditorExecutionRequest(SqlEditorExecuteRequest request) {
        DbDmlExecutionRequest param = new DbDmlExecutionRequest();
        param.setExecuteRequest(request2param(request));
        param.setSource(SqlOperationLogSourceEnum.SQL_EDITOR_HTTP.name());
        return param;
    }

    public DbDmlExecutionRequest tableBrowseExecutionRequest(TableBrowseRequest request) {
        DbDmlExecutionRequest param = new DbDmlExecutionRequest();
        param.setExecuteRequest(request2param(request));
        param.setSource(SqlOperationLogSourceEnum.TABLE_BROWSE.name());
        return param;
    }

    public DbDmlExecutionRequest tableEditExecutionRequest(TableEditExecuteRequest request) {
        DbDmlExecutionRequest param = new DbDmlExecutionRequest();
        param.setExecuteRequest(request2param(request));
        param.setSource(SqlOperationLogSourceEnum.TABLE_EDIT.name());
        return param;
    }

    public DbDmlExecutionRequest ddlExecutionRequest(DdlExecuteRequest request) {
        DbDmlExecutionRequest param = new DbDmlExecutionRequest();
        param.setExecuteRequest(request2param(request));
        param.setSource(SqlOperationLogSourceEnum.SQL_EDITOR_HTTP.name());
        return param;
    }

    public TableSelector tableSelector(boolean columnList, boolean indexList) {
        TableSelector selector = new TableSelector();
        selector.setColumnList(columnList);
        selector.setIndexList(indexList);
        return selector;
    }

    public DbTablePinRequest tableBriefRequest2pinParam(TableBriefQueryRequest request, Long userId) {
        if (request == null) {
            return null;
        }
        DbTablePinRequest param = new DbTablePinRequest();
        param.setUserId(userId);
        param.setDataSourceId(request.getDataSourceId());
        param.setDatabaseName(request.getDatabaseName());
        param.setSchemaName(request.getSchemaName());
        return param;
    }

    public Table normalizeModifyTable(TableModifySqlRequest request) {
        if (request == null || request.getNewTable() == null) {
            return null;
        }
        Table table = request.getNewTable();
        table.setSchemaName(request.getSchemaName());
        table.setDatabaseName(request.getDatabaseName());
        if (CollectionUtils.isNotEmpty(table.getColumnList())) {
            List<TableColumn> columnList = new ArrayList<>();
            for (TableColumn tableColumn : table.getColumnList()) {
                tableColumn.setSchemaName(request.getSchemaName());
                tableColumn.setTableName(table.getName());
                tableColumn.setDatabaseName(request.getDatabaseName());
                if (!StringUtils.isBlank(tableColumn.getName())) {
                    columnList.add(tableColumn);
                }
            }
            table.setColumnList(columnList);
        }
        if (CollectionUtils.isNotEmpty(table.getIndexList())) {
            List<TableIndex> tableIndexList = new ArrayList<>();
            for (TableIndex tableIndex : table.getIndexList()) {
                tableIndex.setSchemaName(request.getSchemaName());
                tableIndex.setTableName(table.getName());
                tableIndex.setDatabaseName(request.getDatabaseName());
                if (!StringUtils.isBlank(tableIndex.getName())) {
                    tableIndexList.add(tableIndex);
                }
            }
            table.setIndexList(tableIndexList);
        }
        return table;
    }

    public abstract DbTableCopyRequest request2param(TableCopyRequest request);

    public DbConnectionContextRequest connectionContextParam(Long dataSourceId, String databaseName, String schemaName) {
        DbConnectionContextRequest param = new DbConnectionContextRequest();
        param.setDataSourceId(dataSourceId);
        param.setDatabaseName(databaseName);
        param.setSchemaName(schemaName);
        return param;
    }

    public abstract TableMilvusQueryRequest request2request(TableBriefQueryRequest request);

    @Mappings({
            @Mapping(source = "databaseName", target = "database"),
            @Mapping(source = "schemaName", target = "schema"),
    })
    public abstract DbTableVectorRequest param2param(TableBriefQueryRequest request);

    public abstract TableSchemaRequest req2req(TableBriefQueryRequest request);

    public abstract DbTablePageQueryRequest schemaReq2page(TableSchemaRequest request);

    public abstract DbDmlSqlCopyRequest dmlRequest2param(
            ai.chat2db.community.web.api.model.request.db.DmlSqlCopyRequest request);

    public abstract DbSqlKeywordRequest request2param(SqlKeywordRequest request);

    public abstract DbSqlContextParserRequest request2param(SqlContextParserRequest request);

    public abstract DbSqlFormatRequest request2param(SqlFormatRequest request);

    public abstract DbSqlValidSelectRequest request2param(SqlValidSelectRequest request);

    public DbSqlCompletionGetRequest request2completionParam(SqlCompletionRequest request) {
        if (request == null) {
            return null;
        }
        DbSqlCompletionGetRequest param = new DbSqlCompletionGetRequest();
        param.setConsoleId(request.getConsoleId());
        param.setDataSourceId(request.getDataSourceId());
        param.setDatabaseName(request.getDatabaseName());
        param.setSchemaName(request.getSchemaName());
        String beforeSql = StringUtils.defaultString(request.getBeforeSql());
        String afterSql = StringUtils.defaultString(request.getAfterSql());
        String sql = StringUtils.isNotEmpty(request.getSql()) ? request.getSql() : beforeSql + afterSql;
        param.setSql(sql);
        param.setCursor(request.getCursor() == null ? beforeSql.length() : request.getCursor());
        param.setNeedFullName(request.isNeedFullName());
        param.setKeywordCase(request.getKeywordCase());
        param.setActiveSnippetSlot(request.getActiveSnippetSlot());
        return param;
    }
    public abstract DbSqlHoverRequest request2param(SqlHoverRequest request);
    public abstract ModifyView request2Param(ModifyViewRequest request);

    public abstract DbViewMetaModifyRequest request2param(
            ai.chat2db.community.web.api.model.request.db.ModifyViewMetaRequest request) ;

    public abstract DbViewDeleteRequest request2param(
            ai.chat2db.community.web.api.model.request.db.DeleteViewRequest request);

    @Mapping(target = "applyId", ignore = true)
    public abstract DbDlExecuteRequest request2param(McpExecuteSqlRequest request);

    public abstract AccountOperationRequest request2command(AccountCommandRequest request);

    public abstract AccountCapabilityResponse accountCapability2response(AccountManagerCapability capability);

    public abstract AccountResponse account2response(AccountInfo account);

    public abstract AccountPreviewResponse accountPreview2response(AccountPreview preview);

    public abstract ai.chat2db.community.web.api.model.response.db.AccountExecuteResponse accountExecute2response(
            ai.chat2db.community.domain.api.model.account.AccountExecuteResponse result);

    public abstract DbDatabaseDeletePrepareRequest request2param(DatabaseDeletePrepareRequest request);

    public abstract DbSchemaDeletePrepareRequest request2param(SchemaDeletePrepareRequest request);

    public abstract DbDatabaseObjectDeleteExecuteRequest request2param(DatabaseObjectDeleteExecuteRequest request);

    public abstract DatabaseObjectDeletePrepareResponse databaseObjectDeletePrepare2response(
            DatabaseObjectDeletePrepare prepare);
}
