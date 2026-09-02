package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbFunctionService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.db.FunctionDetailRequest;
import ai.chat2db.community.web.api.model.request.db.FunctionPageRequest;
import ai.chat2db.community.domain.api.model.metadata.Function;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes relational database function metadata endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/function")
@RestController
public class DbFunctionController {

    @Autowired
    private IDbFunctionService functionService;

    /**
     * Lists database functions.
     * <p>
     * Endpoint: {@code GET /api/rdb/function/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return paged web result containing function.
     */
    @GetMapping("/list")
    public WebPageResult<Function> list(@Valid FunctionPageRequest request) {
        List<Function> functions = functionService.functions(request.getDatabaseName(),
            request.getSchemaName());
        if (functions == null) {
            functions = List.of();
        }
        int size = functions.size();
        return WebPageResult.of(functions, Long.valueOf(size), 1, size);
    }

    /**
     * Gets database functions.
     * <p>
     * Endpoint: {@code GET /api/rdb/function/detail}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing function.
     */
    @GetMapping("/detail")
    public DataResult<Function> detail(@Valid FunctionDetailRequest request) {
        return DataResult.of(
                functionService.detail(request.getDatabaseName(), request.getSchemaName(), request.getFunctionName()));
    }

    /**
     * Deletes a database function.
     * <p>
     * Endpoint: {@code POST /api/rdb/function/delete}.
     *
     * @param request request payload containing function info.
     * @return operation result for the request.
     */
    @PostMapping("/delete")
    public ActionResult delete(@RequestBody @Valid FunctionDetailRequest request) {
        functionService.drop(request.getDatabaseName(), request.getSchemaName(), request.getFunctionName());
        return ActionResult.isSuccess();
    }
}
