package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbTriggerService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.db.TriggerDetailRequest;
import ai.chat2db.community.web.api.model.request.db.TriggerPageRequest;
import ai.chat2db.community.domain.api.model.metadata.Trigger;
import jakarta.validation.Valid;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes relational database trigger metadata endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/trigger")
@RestController
public class DbTriggerController {

    @Autowired
    private IDbTriggerService triggerService;

    /**
     * Lists database triggers.
     * <p>
     * Endpoint: {@code GET /api/rdb/trigger/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return paged web result containing trigger.
     */
    @GetMapping("/list")
    public WebPageResult<Trigger> list(@Valid TriggerPageRequest request) {
        List<Trigger> triggers = triggerService.triggers(request.getDatabaseName(), request.getSchemaName());
        Long total = CollectionUtils.isNotEmpty(triggers) ? Long.valueOf(triggers.size()) : 0L;
        int size = CollectionUtils.isNotEmpty(triggers) ? triggers.size() : 0;
        return WebPageResult.of(triggers, total, 1, size);
    }

    /**
     * Gets database triggers.
     * <p>
     * Endpoint: {@code GET /api/rdb/trigger/detail}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing trigger.
     */
    @GetMapping("/detail")
    public DataResult<Trigger> detail(@Valid TriggerDetailRequest request) {
        return DataResult.of(
                triggerService.detail(request.getDatabaseName(), request.getSchemaName(), request.getTriggerName()));
    }

    /**
     * Deletes a database trigger.
     * <p>
     * Endpoint: {@code POST /api/rdb/trigger/delete}.
     *
     * @param request request payload containing trigger info.
     * @return operation result for the request.
     */
    @PostMapping("/delete")
    public ActionResult delete(@RequestBody @Valid TriggerDetailRequest request) {
        triggerService.drop(request.getDatabaseName(), request.getSchemaName(), request.getTriggerName());
        return ActionResult.isSuccess();
    }
}
