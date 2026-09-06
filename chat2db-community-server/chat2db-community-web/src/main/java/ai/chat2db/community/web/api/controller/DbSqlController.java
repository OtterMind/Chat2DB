package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.result.DbExplainCapability;
import ai.chat2db.community.domain.api.model.result.DbExplainResult;
import ai.chat2db.community.domain.api.service.db.IDbExplainService;
import ai.chat2db.community.domain.api.service.db.IDbSqlService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.sql.SqlExplainCancelRequest;
import ai.chat2db.community.web.api.model.request.sql.SqlExplainRequest;
import ai.chat2db.community.web.api.model.request.sql.SqlFormatRequest;
import ai.chat2db.community.web.api.model.request.sql.SqlValidSelectRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes SQL formatting, validation, and execution plan endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/sql")
@RestController
public class DbSqlController {

    private final IDbSqlService sqlService;
    private final IDbExplainService explainService;
    private final DbWebConverter dbWebConverter;

    public DbSqlController(IDbSqlService sqlService, IDbExplainService explainService, DbWebConverter dbWebConverter) {
        this.sqlService = sqlService;
        this.explainService = explainService;
        this.dbWebConverter = dbWebConverter;
    }

    /**
     * Lists SQL records.
     * <p>
     * Endpoint: {@code GET /api/sql/format}.
     *
     * @param sqlFormatRequest request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @GetMapping("/format")
    public DataResult<String> format(@Valid SqlFormatRequest sqlFormatRequest) {
        return DataResult.of(sqlService.format(dbWebConverter.request2param(sqlFormatRequest)));
    }

    /**
     * Validates select.
     * <p>
     * Endpoint: {@code GET /api/sql/valid_select}.
     *
     * @param sqlValidSelectRequest request payload or query parameters for the operation.
     * @return data result containing boolean.
     */
    @GetMapping("/valid_select")
    public DataResult<Boolean> validSelect(@Valid SqlValidSelectRequest sqlValidSelectRequest) {
        return DataResult.of(sqlService.validSelect(dbWebConverter.request2param(sqlValidSelectRequest)));
    }

    /**
     * Returns EXPLAIN FORMAT=JSON output for a SQL statement.
     * <p>
     * Endpoint: {@code POST /api/sql/explain_json}.
     *
     * @param request the SQL statement and datasource context to explain.
     * @return data result containing the raw JSON plan.
     */
    @PostMapping("/explain_json")
    public DataResult<DbExplainResult> explainJson(@Valid @RequestBody SqlExplainRequest request) {
        return DataResult.of(explainService.explainJson(request.getSql(), request.getRequestId()));
    }

    /**
     * Returns EXPLAIN ANALYZE output for a SQL statement (MySQL 8.0.18+).
     * <p>
     * Endpoint: {@code POST /api/sql/explain_analyze}.
     *
     * @param request the SQL statement and datasource context to analyze.
     * @return data result containing the raw analyze output.
     */
    @PostMapping("/explain_analyze")
    public DataResult<DbExplainResult> explainAnalyze(@Valid @RequestBody SqlExplainRequest request) {
        return DataResult.of(explainService.explainAnalyze(request.getSql(), request.getRequestId()));
    }

    /**
     * Returns EXPLAIN feature support for the current datasource.
     *
     * @param request datasource context and a placeholder SQL value for connection binding.
     * @return data result containing current EXPLAIN capability.
     */
    @PostMapping("/explain_capability")
    public DataResult<DbExplainCapability> explainCapability(@Valid @RequestBody SqlExplainRequest request) {
        return DataResult.of(explainService.capability());
    }

    /**
     * Cancels a running EXPLAIN/ANALYZE request by frontend-owned request id.
     *
     * @param request cancel request.
     * @return data result containing whether a running statement was found.
     */
    @PostMapping("/explain_cancel")
    public DataResult<Boolean> explainCancel(@Valid @RequestBody SqlExplainCancelRequest request) {
        return DataResult.of(explainService.cancel(request.getRequestId()));
    }
}
