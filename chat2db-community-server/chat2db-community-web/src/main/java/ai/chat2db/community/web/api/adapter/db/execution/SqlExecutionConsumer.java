package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.domain.api.service.db.IDbExecuteResultEnhanceService;
import ai.chat2db.community.domain.api.service.db.IDbLargeValueTokenService;
import ai.chat2db.community.domain.api.model.request.db.DbLargeValueTokensAttachRequest;
import ai.chat2db.community.domain.api.model.request.db.DbExecuteResultEnhanceRequest;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SqlExecutionConsumer implements ISqlExecutionResultConsumer {

    private final SqlExecutionRequest request;
    private final ISqlExecutionSink sink;
    private final DbWebConverter dbWebConverter;
    private final IDbLargeValueTokenService largeValueTokenService;
    private final IDbExecuteResultEnhanceService executeResultEnhanceService;
    private final SqlExecutionEventContext eventContext;

    public SqlExecutionConsumer(SqlExecutionRequest request, ISqlExecutionSink sink,
                                DbWebConverter dbWebConverter,
                                IDbLargeValueTokenService largeValueTokenService,
                                IDbExecuteResultEnhanceService executeResultEnhanceService,
                                SqlExecutionEventContext eventContext) {
        this.request = request;
        this.sink = sink;
        this.dbWebConverter = dbWebConverter;
        this.largeValueTokenService = largeValueTokenService;
        this.executeResultEnhanceService = executeResultEnhanceService;
        this.eventContext = eventContext;
    }

    @Override
    public void statementStarted(String sql, String originalSql, String comment) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sql", sql);
        payload.put("originalSql", originalSql);
        payload.put("comment", comment);
        synchronized (eventContext) {
            SqlExecutionEventIdentity identity = eventContext.statementStarted();
            sink.send("statementStarted", payload, identity);
        }
    }

    @Override
    public void resultStarted(ExecuteResponse result) {
        enhanceHeader(result);
        attachLargeValueTokens(result);
        synchronized (eventContext) {
            SqlExecutionEventIdentity identity = eventContext.resultActive(result);
            sink.send("resultStarted", dbWebConverter.dto2response(result), identity);
        }
    }

    @Override
    public void rows(ExecuteResponse result, List<List<ResultCell>> rows) {
        ExecuteResponse chunk = ExecuteResponse.builder()
                .success(result.getSuccess())
                .sql(result.getSql())
                .originalSql(result.getOriginalSql())
                .description(result.getDescription())
                .headerList(result.getHeaderList())
                .dataList(rows)
                .sqlType(result.getSqlType())
                .resultSetId(result.getResultSetId())
                .extra(result.getExtra())
                .canEdit(result.isCanEdit())
                .tableName(result.getTableName())
                .pageNo(result.getPageNo())
                .pageSize(result.getPageSize())
                .fuzzyTotal(result.getFuzzyTotal())
                .hasNextPage(result.getHasNextPage())
                .build();
        attachLargeValueTokens(chunk);
        synchronized (eventContext) {
            SqlExecutionEventIdentity identity = eventContext.currentIdentity();
            sink.send("rows", dbWebConverter.dto2response(chunk), identity);
        }
    }

    @Override
    public void resultFinished(ExecuteResponse result) {
        attachLargeValueTokens(result);
        synchronized (eventContext) {
            SqlExecutionEventIdentity identity = eventContext.resultActive(result);
            sink.send("resultFinished", dbWebConverter.dto2response(result), identity);
        }
    }

    @Override
    public void updateCount(ExecuteResponse result) {
        synchronized (eventContext) {
            SqlExecutionEventIdentity identity = eventContext.resultActive(result);
            sink.send("updateCount", dbWebConverter.dto2response(result), identity);
        }
    }

    @Override
    public void statementFinished(String sql, long duration) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sql", sql);
        payload.put("duration", duration);
        synchronized (eventContext) {
            SqlExecutionEventIdentity identity = eventContext.statementFinished();
            sink.send("statementFinished", payload, identity);
        }
    }

    private void enhanceHeader(ExecuteResponse result) {
        if (result == null) {
            return;
        }
        DbExecuteResultEnhanceRequest enhanceExecuteResultRequest = new DbExecuteResultEnhanceRequest();
        enhanceExecuteResultRequest.setExecuteResult(result);
        enhanceExecuteResultRequest.setDataSourceId(request.getSqlEditorRequest().getDataSourceId());
        enhanceExecuteResultRequest.setDatabaseName(request.getSqlEditorRequest().getDatabaseName());
        enhanceExecuteResultRequest.setSchemaName(request.getSqlEditorRequest().getSchemaName());
        executeResultEnhanceService.enhance(enhanceExecuteResultRequest);
    }

    private void attachLargeValueTokens(ExecuteResponse result) {
        if (result == null) {
            return;
        }
        DbLargeValueTokensAttachRequest attachLargeValueTokensRequest = new DbLargeValueTokensAttachRequest();
        attachLargeValueTokensRequest.setDataSourceId(request.getSqlEditorRequest().getDataSourceId());
        attachLargeValueTokensRequest.setDatabaseName(request.getSqlEditorRequest().getDatabaseName());
        attachLargeValueTokensRequest.setSchemaName(request.getSqlEditorRequest().getSchemaName());
        attachLargeValueTokensRequest.setTableName(result.getTableName());
        attachLargeValueTokensRequest.setHeaders(result.getHeaderList());
        attachLargeValueTokensRequest.setDataList(result.getDataList());
        attachLargeValueTokensRequest.setCanEdit(result.isCanEdit());
        largeValueTokenService.attachTokens(attachLargeValueTokensRequest);
    }
}
