package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbProcedureService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.db.ProcedureDetailRequest;
import ai.chat2db.community.web.api.model.request.db.ProcedurePageRequest;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes relational database procedure metadata endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/procedure")
@RestController
public class DbProcedureController {

    @Autowired
    private IDbProcedureService procedureService;

    /**
     * Lists database procedures.
     * <p>
     * Endpoint: {@code GET /api/rdb/procedure/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return paged web result containing procedure.
     */
    @GetMapping("/list")
    public WebPageResult<Procedure> list(@Valid ProcedurePageRequest request) {
        List<Procedure> procedures = procedureService.procedures(request.getDatabaseName(),
            request.getSchemaName());
        if (procedures == null) {
            procedures = List.of();
        }
        int size = procedures.size();
        return WebPageResult.of(procedures, Long.valueOf(size), 1, size);
    }

    /**
     * Gets database procedures.
     * <p>
     * Endpoint: {@code GET /api/rdb/procedure/detail}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing procedure.
     */
    @GetMapping("/detail")
    public DataResult<Procedure> detail(@Valid ProcedureDetailRequest request) {
        return DataResult.of(
                procedureService.detail(request.getDatabaseName(), request.getSchemaName(), request.getProcedureName()));
    }

    /**
     * Deletes a database procedure.
     * <p>
     * Endpoint: {@code POST /api/rdb/procedure/delete}.
     *
     * @param request request payload containing procedure info.
     * @return operation result for the request.
     */
    @PostMapping("/delete")
    public ActionResult delete(@RequestBody @Valid ProcedureDetailRequest request) {
        procedureService.drop(request.getDatabaseName(), request.getSchemaName(), request.getProcedureName());
        return ActionResult.isSuccess();
    }
}
