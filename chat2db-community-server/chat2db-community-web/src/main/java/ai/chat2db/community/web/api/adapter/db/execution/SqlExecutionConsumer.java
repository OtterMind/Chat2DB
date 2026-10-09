package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.domain.api.service.db.IDbExecuteResultEnhanceService;
import ai.chat2db.community.domain.api.service.db.IDbLargeValueTokenService;
import ai.chat2db.community.domain.api.model.request.db.DbLargeValueTokensAttachRequest;
import ai.chat2db.community.domain.api.model.request.db.DbExecuteResultEnhanceRequest;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshot;
import ai.chat2db.community.domain.api.service.result.IResultSnapshotStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SqlExecutionConsumer implements ISqlExecutionResultConsumer {

    private final SqlExecutionRequest request;
    private final ISqlExecutionSink sink;
    private final DbWebConverter dbWebConverter;
    private final IDbLargeValueTokenService largeValueTokenService;
    private final IDbExecuteResultEnhanceService executeResultEnhanceService;
    private final SqlExecutionEventContext eventContext;
    private final IResultSnapshotStore resultSnapshotStore;

    /**
     * One snapshot per result of this execution, keyed so a re-sent result replaces its snapshot instead of piling up.
     */
    private final Map<String, ResultSnapshot> snapshotsByResult = new ConcurrentHashMap<>();

    /**
     * Number of rows already captured per result, used to keep cell positions absolute across row batches.
     */
    private final Map<String, Integer> capturedRowCounts = new ConcurrentHashMap<>();

    public SqlExecutionConsumer(SqlExecutionRequest request, ISqlExecutionSink sink,
                                DbWebConverter dbWebConverter,
                                IDbLargeValueTokenService largeValueTokenService,
                                IDbExecuteResultEnhanceService executeResultEnhanceService,
                                SqlExecutionEventContext eventContext,
                                IResultSnapshotStore resultSnapshotStore) {
        this.request = request;
        this.sink = sink;
        this.dbWebConverter = dbWebConverter;
        this.largeValueTokenService = largeValueTokenService;
        this.executeResultEnhanceService = executeResultEnhanceService;
        this.eventContext = eventContext;
        this.resultSnapshotStore = resultSnapshotStore;
    }

    /**
     * Called once the execution is done. The captured content is moved to disk and the heap it used is released, but
     * the snapshot itself stays readable while the large-value read tokens are valid, so a copy that starts right
     * after the query still gets the complete value. Expired snapshots are removed by the periodic store sweep.
     */
    public void close() {
        for (ResultSnapshot snapshot : new ArrayList<>(snapshotsByResult.values())) {
            String snapshotId = snapshot.getSnapshotId();
            try {
                resultSnapshotStore.flushToDisk(snapshotId);
            } catch (RuntimeException e) {
                // Closing must never break the execution teardown: a failed flush only costs the in-memory copy.
                log.warn("Failed to flush result snapshot {} on close: {}", snapshotId, e.getMessage());
            }
            // Dropping the lease makes the snapshot releasable, so an unused capture goes away immediately instead of
            // waiting for the snapshot TTL.
            resultSnapshotStore.unhold(snapshotId);
            largeValueTokenService.releaseUnusedFor(snapshotId);
        }
        snapshotsByResult.clear();
        capturedRowCounts.clear();
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
        synchronized (eventContext) {
            SqlExecutionEventIdentity identity = eventContext.resultActive(result);
            sink.send("resultFinished", dbWebConverter.dto2completionResponse(result), identity);
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
        String key = resultKey(result);
        boolean captures = containsLargeValue(result.getDataList());
        if (!captures && !snapshotsByResult.containsKey(key)) {
            // A result without a large value costs nothing: only the absolute row offset has to stay in sync so a later
            // batch with a large value still reports the right positions.
            advanceCapturedRows(result);
            return;
        }
        DbLargeValueTokensAttachRequest attachLargeValueTokensRequest = new DbLargeValueTokensAttachRequest();
        attachLargeValueTokensRequest.setTableName(result.getTableName());
        attachLargeValueTokensRequest.setHeaders(result.getHeaderList());
        attachLargeValueTokensRequest.setDataList(result.getDataList());
        SnapshotAssignment assignment = snapshotFor(result);
        attachLargeValueTokensRequest.setSnapshotId(assignment.snapshot().getSnapshotId());
        attachLargeValueTokensRequest.setRowOffset(assignment.rowOffset());
        largeValueTokenService.attachTokens(attachLargeValueTokensRequest);
    }

    private boolean containsLargeValue(List<List<ResultCell>> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return false;
        }
        for (List<ResultCell> row : dataList) {
            if (row == null) {
                continue;
            }
            for (ResultCell cell : row) {
                if (cell != null && cell.isLargeValue()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void advanceCapturedRows(ExecuteResponse result) {
        String key = resultKey(result);
        int rowOffset = capturedRowCounts.getOrDefault(key, 0);
        capturedRowCounts.put(key, rowOffset + (result.getDataList() == null ? 0 : result.getDataList().size()));
    }

    /**
     * Returns the snapshot of this result, creating it on first use. Row batches of the same result share one snapshot
     * so cell positions stay absolute; a re-run of the same result set starts a fresh snapshot and releases the old one.
     */
    private SnapshotAssignment snapshotFor(ExecuteResponse result) {
        String key = resultKey(result);
        ResultSnapshot snapshot = snapshotsByResult.get(key);
        if (snapshot == null) {
            snapshot = resultSnapshotStore.register();
            // The execution keeps a lease while it is still capturing, so a client release can not free the snapshot
            // in the middle of a streamed result.
            resultSnapshotStore.hold(snapshot.getSnapshotId());
            ResultSnapshot previous = snapshotsByResult.put(key, snapshot);
            if (previous != null) {
                resultSnapshotStore.unhold(previous.getSnapshotId());
                resultSnapshotStore.release(previous.getSnapshotId());
            }
            capturedRowCounts.put(key, 0);
        }
        int rowOffset = capturedRowCounts.getOrDefault(key, 0);
        int rowCount = result.getDataList() == null ? 0 : result.getDataList().size();
        capturedRowCounts.put(key, rowOffset + rowCount);
        return new SnapshotAssignment(snapshot, rowOffset);
    }

    private record SnapshotAssignment(ResultSnapshot snapshot, int rowOffset) {
    }

    private String resultKey(ExecuteResponse result) {
        if (result.getResultSetId() != null) {
            return "result-set-" + result.getResultSetId();
        }
        if (result.getStatementSequence() != null) {
            return "statement-" + result.getStatementSequence();
        }
        return "default";
    }



}
