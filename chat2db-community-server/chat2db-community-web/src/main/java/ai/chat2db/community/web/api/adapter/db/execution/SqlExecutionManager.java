package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbExecuteResultEnhanceService;
import ai.chat2db.community.domain.api.service.db.IDbLargeValueTokenService;
import ai.chat2db.community.domain.api.service.result.IResultSnapshotStore;
import ai.chat2db.community.domain.api.service.db.IDbSqlExecutionService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class SqlExecutionManager {

    private final ThreadPoolExecutor executionPool = new ThreadPoolExecutor(
            4,
            16,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            new SqlExecutionThreadFactory("chat2db-sql-execution")
    );
    private final ExecutorService cancelPool = new ThreadPoolExecutor(
            1,
            4,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            new SqlExecutionThreadFactory("chat2db-sql-cancel")
    );
    private final ScheduledExecutorService outputPoller =
            new ScheduledThreadPoolExecutor(1, new SqlExecutionThreadFactory("chat2db-sql-output"));

    private final Map<String, SqlExecutionJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> laneExecutions = new ConcurrentHashMap<>();

    private final IDbConnectionContextService connectionContextService;
    private final IDbSqlExecutionService sqlExecutionService;
    private final DbWebConverter dbWebConverter;
    private final IDbLargeValueTokenService largeValueTokenService;
    private final IDbExecuteResultEnhanceService executeResultEnhanceService;
    private final IOpsSqlOperationLogService sqlOperationLogRecorder;
    private final IResultSnapshotStore resultSnapshotStore;

    public SqlExecutionManager(IDbConnectionContextService connectionContextService,
                               IDbSqlExecutionService sqlExecutionService,
                               DbWebConverter dbWebConverter,
                               IDbLargeValueTokenService largeValueTokenService,
                               IDbExecuteResultEnhanceService executeResultEnhanceService,
                               IOpsSqlOperationLogService sqlOperationLogRecorder,
                               IResultSnapshotStore resultSnapshotStore) {
        this.connectionContextService = connectionContextService;
        this.sqlExecutionService = sqlExecutionService;
        this.dbWebConverter = dbWebConverter;
        this.largeValueTokenService = largeValueTokenService;
        this.executeResultEnhanceService = executeResultEnhanceService;
        this.sqlOperationLogRecorder = sqlOperationLogRecorder;
        this.resultSnapshotStore = resultSnapshotStore;
        outputPoller.scheduleAtFixedRate(this::pollMessages, 200, 200, TimeUnit.MILLISECONDS);
    }

    public SqlExecutionStartResult start(SqlExecutionRequest request) {
        String executionId = UUID.randomUUID().toString();
        request.setExecutionId(executionId);
        String laneId = StringUtils.defaultIfBlank(request.getLaneId(), executionId);
        request.setLaneId(laneId);
        String existingExecutionId = laneExecutions.putIfAbsent(laneId, executionId);
        if (existingExecutionId != null) {
            throw new BusinessException("sqlExecution.alreadyRunningInEditor");
        }
        ISqlExecutionSink sink = new ConsoleSqlExecutionSink(request.getRequestUuid(), executionId);
        SqlExecutionJob job = new SqlExecutionJob(request, sink, connectionContextService, sqlExecutionService,
                dbWebConverter,
                largeValueTokenService, executeResultEnhanceService, sqlOperationLogRecorder, resultSnapshotStore,
                this::onJobFinished);
        jobs.put(executionId, job);
        try {
            Future<?> future = executionPool.submit(job);
            job.setFuture(future);
            return new SqlExecutionStartResult(executionId);
        } catch (RuntimeException e) {
            jobs.remove(executionId);
            laneExecutions.remove(laneId, executionId);
            throw e;
        }
    }

    public boolean cancel(String executionId) {
        SqlExecutionJob job = jobs.get(executionId);
        if (job == null) {
            return false;
        }
        if (!job.hasStarted()) {
            Future<?> future = job.getFuture();
            if (future != null && future.cancel(true)) {
                job.sendCancelled();
                onJobFinished(job);
                return true;
            }
        }
        cancelPool.submit(job::cancel);
        return true;
    }

    private void pollMessages() {
        for (SqlExecutionJob job : jobs.values()) {
            job.pollMessages();
        }
    }

    private void onJobFinished(SqlExecutionJob job) {
        String executionId = job.getRequest().getExecutionId();
        jobs.remove(executionId);
        laneExecutions.remove(job.getRequest().getLaneId(), executionId);
    }

    @PreDestroy
    public void shutdown() {
        outputPoller.shutdownNow();
        cancelPool.shutdownNow();
        executionPool.shutdownNow();
    }
}
