package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbExplainService;
import ai.chat2db.community.domain.api.service.db.IDbSqlService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
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
     * @param sql the SQL statement to explain.
     * @return data result containing the raw JSON plan.
     */
    @PostMapping("/explain_json")
    public DataResult<String> explainJson(@RequestBody String sql) {
        return DataResult.of(explainService.explainJson(sql));
    }

    /**
     * Returns EXPLAIN ANALYZE output for a SQL statement (MySQL 8.0.18+).
     * <p>
     * Endpoint: {@code POST /api/sql/explain_analyze}.
     *
     * @param sql the SQL statement to analyze.
     * @return data result containing the raw analyze output.
     */
    @PostMapping("/explain_analyze")
    public DataResult<String> explainAnalyze(@RequestBody String sql) {
        return DataResult.of(explainService.explainAnalyze(sql));
    }
}
