package ai.chat2db.community.web.api.controller;

import jakarta.validation.Valid;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.operation.Operation;
import ai.chat2db.community.domain.api.service.ops.IOpsOperationSavedService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.converter.operation.log.OperationLogConverter;
import ai.chat2db.community.web.api.model.request.operation.saved.BatchTabCloseRequest;
import ai.chat2db.community.web.api.model.request.operation.saved.OperationQueryRequest;
import org.springframework.web.bind.annotation.*;

/**
 * Manages saved operation tabs and recent editor state.
 */
@RequestMapping("/api/operation/saved")
@RestController
public class OpsOperationSavedController {

    private final IOpsOperationSavedService operationSavedService;
    private final OperationLogConverter operationLogConverter;

    public OpsOperationSavedController(IOpsOperationSavedService operationSavedService,
            OperationLogConverter operationLogConverter) {
        this.operationSavedService = operationSavedService;
        this.operationLogConverter = operationLogConverter;
    }

    /**
     * Lists saved operations.
     * <p>
     * Endpoint: {@code GET /api/operation/saved/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return paged web result containing operation.
     */
    @GetMapping("/list")
    public WebPageResult<Operation> list(OperationQueryRequest request) {
        PageResponse<Operation> pageResult = operationSavedService.consoleList(operationLogConverter.request2param(request));
        return WebPageResult.of(pageResult.getData(), pageResult.getTotal(), request.getPageNo(), request.getPageSize());
    }

    /**
     * Gets saved operations.
     * <p>
     * Endpoint: {@code GET /api/operation/saved}.
     *
     * @param id identifier used to locate the target resource.
     * @return data result containing operation.
     */
    @GetMapping("")
    public DataResult<Operation> get(@RequestParam("id") Long id) {
        return DataResult.of(operationSavedService.getConsole(id));
    }

    /**
     * Creates saved operations.
     * <p>
     * Endpoint: {@code POST /api/operation/saved/create}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing long.
     */
    @PostMapping("/create")
    public DataResult<Long> create(@Valid @RequestBody Operation request) {
        return DataResult.of(operationSavedService.createConsole(request));
    }

    /**
     * Updates saved operations.
     * <p>
     * Endpoint: {@code POST/PUT /api/operation/saved/update}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @RequestMapping(value = "/update", method = {RequestMethod.POST, RequestMethod.PUT})
    public ActionResult update(@Valid @RequestBody Operation request) {
        operationSavedService.updateConsole(request);
        return ActionResult.isSuccess();
    }

    /**
     * Deletes saved operations.
     * <p>
     * Endpoint: {@code DELETE /api/operation/saved}.
     *
     * @param id identifier used to locate the target resource.
     * @return operation result for the request.
     */
    @DeleteMapping(value = "")
    public ActionResult delete(@RequestParam("id") Long id) {
        operationSavedService.deleteConsole(id);
        return ActionResult.isSuccess();
    }

    /**
     * Handles batch tab close for saved operations.
     * <p>
     * Endpoint: {@code POST/PUT /api/operation/saved/batch_tab_close}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @RequestMapping(value = "/batch_tab_close", method = {RequestMethod.POST, RequestMethod.PUT})
    public ActionResult batchTabClose(@Valid @RequestBody BatchTabCloseRequest request) {
        operationSavedService.closeTabs();
        return ActionResult.isSuccess();
    }


}
