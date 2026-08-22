package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.runtime.TransactionStateResponse;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.ConsoleCloseRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages manual transactions scoped to a single SQL Console: begin, commit, rollback,
 * state lookup, and release on console close / connection switch.
 *
 * <p>Each request carries {@code consoleId} via {@link ConsoleCloseRequest} so the
 * {@link ConnectionInfoHandler} aspect binds the console-scoped connection context, and the
 * bound connection is reused across executions while a transaction is open.
 */
@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/rdb/transaction")
@RestController
public class DbTransactionController {

    @Autowired
    private IDbConnectionContextService connectionContextService;

    /**
     * Begins a manual transaction for the console.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/begin}.
     */
    @RequestMapping(value = "/begin", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<TransactionStateResponse> begin(@Valid @RequestBody ConsoleCloseRequest request) {
        return DataResult.of(connectionContextService.beginManualTransaction(toContext(request)));
    }

    /**
     * Commits the console's open transaction.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/commit}.
     */
    @RequestMapping(value = "/commit", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<TransactionStateResponse> commit(@Valid @RequestBody ConsoleCloseRequest request) {
        return DataResult.of(connectionContextService.commitTransaction(toContext(request)));
    }

    /**
     * Rolls back the console's open transaction.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/rollback}.
     */
    @RequestMapping(value = "/rollback", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<TransactionStateResponse> rollback(@Valid @RequestBody ConsoleCloseRequest request) {
        return DataResult.of(connectionContextService.rollbackTransaction(toContext(request)));
    }

    /**
     * Returns the console's current transaction state.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/state}.
     */
    @RequestMapping(value = "/state", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<TransactionStateResponse> state(@Valid @RequestBody ConsoleCloseRequest request) {
        return DataResult.of(connectionContextService.getTransactionState(toContext(request)));
    }

    /**
     * Releases the console's bound connection (rolls back any open transaction first). Called
     * when the console is closed or its connection changes.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/release}.
     */
    @RequestMapping(value = "/release", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<Void> release(@Valid @RequestBody ConsoleCloseRequest request) {
        connectionContextService.releaseBoundConnection(toContext(request));
        return DataResult.empty();
    }

    private DbConnectionContextRequest toContext(ConsoleCloseRequest request) {
        DbConnectionContextRequest context = new DbConnectionContextRequest();
        context.setDataSourceId(request.getDataSourceId());
        context.setConsoleId(request.getConsoleId());
        context.setDatabaseName(request.getDatabaseName());
        context.setSchemaName(request.getSchemaName());
        return context;
    }
}
