package ai.chat2db.community.web.api.controller;

import jakarta.validation.Valid;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.service.ops.IOpsOperationLogQueryService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.converter.operation.log.OperationLogConverter;
import ai.chat2db.community.web.api.model.request.operation.log.OperationLogQueryRequest;
import ai.chat2db.community.web.api.model.response.operation.log.OperationLogResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes operation-log query and export endpoints.
 */
@RequestMapping("/api/operation/log")
@RestController
public class OpsOperationLogController {

    @Autowired
    private OperationLogConverter operationLogConverter;

    private final IOpsOperationLogQueryService operationLogQueryService;

    public OpsOperationLogController(IOpsOperationLogQueryService operationLogQueryService) {
        this.operationLogQueryService = operationLogQueryService;
    }

    /**
     * Lists operation logs.
     * <p>
     * Endpoint: {@code GET /api/operation/log/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return paged web result containing operation log response.
     */
    @GetMapping("/list")
    public WebPageResult<OperationLogResponse> list(OperationLogQueryRequest request) {
        PageResponse<OperationLog> pageResult =
                operationLogQueryService.operationLogPreviewList(operationLogConverter.request2param(request));
        return WebPageResult.of(operationLogConverter.toResponseList(pageResult.getData()),
                pageResult.getTotal(), request.getPageNo(), request.getPageSize());
    }

    /**
     * Creates operation logs.
     * <p>
     * Endpoint: {@code POST /api/operation/log/create}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing long.
     */
    @PostMapping("/create")
    public DataResult<Long> create(@Valid @RequestBody OperationLog request) {
        return DataResult.of(operationLogQueryService.createOperationLog(request));
    }

    /**
     * Gets operation logs.
     * <p>
     * Endpoint: {@code GET /api/operation/log}.
     *
     * @param id identifier used to locate the target resource.
     * @return data result containing operation log response.
     */
    @GetMapping("")
    public DataResult<OperationLogResponse> get(@RequestParam Long id) {
        return DataResult.of(operationLogConverter.toResponse(operationLogQueryService.getOperationLog(id)));
    }

}
