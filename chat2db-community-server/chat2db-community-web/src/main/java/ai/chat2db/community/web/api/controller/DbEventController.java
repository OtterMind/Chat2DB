package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.metadata.Event;
import ai.chat2db.community.domain.api.service.db.IDbEventService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Exposes MySQL Event lifecycle operations (MYSQL-OBJ-013).
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/event")
@RestController
public class DbEventController {

    @Autowired
    private IDbEventService eventService;

    /**
     * Lists events of a database.
     * <p>
     * Endpoint: {@code GET /api/rdb/event/list?dataSourceId=1&databaseName=xxx}.
     */
    @GetMapping("/list")
    public DataResult<List<Map<String, Object>>> list(@Valid DataSourceBaseRequest request) {
        return DataResult.of(eventService.list(request.getDatabaseName()));
    }

    /**
     * Returns the global event_scheduler state.
     * <p>
     * Endpoint: {@code GET /api/rdb/event/scheduler_status?dataSourceId=1&databaseName=xxx}.
     */
    @GetMapping("/scheduler_status")
    public DataResult<Map<String, Object>> schedulerStatus(@Valid DataSourceBaseRequest request) {
        return DataResult.of(eventService.schedulerStatus(request.getDatabaseName()));
    }

    /**
     * Gets a database event and its editable DDL.
     * <p>
     * Endpoint: {@code GET /api/rdb/event/detail?dataSourceId=1&databaseName=xxx&eventName=yyy}.
     */
    @GetMapping("/detail")
    public DataResult<Event> detail(@Valid EventNameRequest request) {
        return DataResult.of(eventService.detail(
                request.getDatabaseName(), request.getSchemaName(), request.getEventName()));
    }

    /**
     * Generates the DROP EVENT statement.
     * <p>
     * Endpoint: {@code POST /api/rdb/event/drop_sql}.
     */
    @RequestMapping(value = "/drop_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> dropSql(@Valid @RequestBody EventNameRequest request) {
        return DataResult.of(eventService.dropEventSql(request.getDatabaseName(), request.getEventName()));
    }

    /**
     * Generates the ALTER EVENT ENABLE/DISABLE statement.
     * <p>
     * Endpoint: {@code POST /api/rdb/event/enabled_sql}.
     */
    @RequestMapping(value = "/enabled_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> enabledSql(@Valid @RequestBody SetEnabledRequest request) {
        return DataResult.of(eventService.setEventEnabledSql(
                request.getDatabaseName(), request.getEventName(), request.getEnabled()));
    }

    @Data
    public static class EventNameRequest extends DataSourceBaseRequest {
        @NotBlank
        private String eventName;
    }

    @Data
    public static class SetEnabledRequest extends EventNameRequest {
        @NotNull
        private Boolean enabled;
    }
}
