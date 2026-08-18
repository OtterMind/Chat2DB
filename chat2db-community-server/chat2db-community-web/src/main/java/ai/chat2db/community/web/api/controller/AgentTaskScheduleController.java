package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentTaskSchedule;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleExecution;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskScheduleCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskScheduleLifecycleRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskScheduleUpdateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskScheduleService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.model.request.agent.AgentTaskScheduleCronPreviewRequest;
import ai.chat2db.community.web.api.model.response.agent.AgentTaskScheduleCronPreviewResponse;
import ai.chat2db.community.web.api.model.response.agent.AgentTaskScheduleDetailResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/agent/task-schedules")
public class AgentTaskScheduleController {

    private final IAgentTaskScheduleService scheduleService;
    private final IIdentityService identityService;

    public AgentTaskScheduleController(IAgentTaskScheduleService scheduleService,
                                       IIdentityService identityService) {
        this.scheduleService = scheduleService;
        this.identityService = identityService;
    }

    @PostMapping
    public DataResult<AgentTaskScheduleDetailResponse> create(
            @RequestBody AgentTaskScheduleCreateRequest request) {
        if (request == null) throw new IllegalArgumentException("schedule request is required");
        request.setCreatedBy(identityService.currentUserId());
        AgentTaskSchedule created = scheduleService.create(request);
        return DataResult.of(detail(created));
    }

    @GetMapping
    public ListResult<AgentTaskSchedule> list() {
        return ListResult.of(scheduleService.list(identityService.currentUserId()));
    }

    @GetMapping("/cron-preview")
    public DataResult<AgentTaskScheduleCronPreviewResponse> preview(
            @ModelAttribute AgentTaskScheduleCronPreviewRequest request) {
        return DataResult.of(new AgentTaskScheduleCronPreviewResponse(
                scheduleService.preview(request.getExpression(), request.getTimezone(), 3)));
    }

    @GetMapping("/{scheduleId}")
    public DataResult<AgentTaskScheduleDetailResponse> get(@PathVariable String scheduleId) {
        AgentTaskSchedule schedule = requireOwner(scheduleId);
        return DataResult.of(detail(schedule));
    }

    @GetMapping("/{scheduleId}/executions")
    public ListResult<AgentTaskScheduleExecution> executions(@PathVariable String scheduleId) {
        requireOwner(scheduleId);
        return ListResult.of(scheduleService.listExecutions(scheduleId));
    }

    @PostMapping("/{scheduleId}")
    public DataResult<AgentTaskScheduleDetailResponse> update(
            @PathVariable String scheduleId,
            @RequestBody AgentTaskScheduleUpdateRequest request) {
        requireOwner(scheduleId);
        if (request == null) throw new IllegalArgumentException("schedule update request is required");
        request.setScheduleId(scheduleId);
        return DataResult.of(detail(scheduleService.update(request)));
    }

    @PostMapping("/{scheduleId}/pause")
    public DataResult<AgentTaskSchedule> pause(
            @PathVariable String scheduleId,
            @RequestBody AgentTaskScheduleLifecycleRequest request) {
        requireOwner(scheduleId);
        return DataResult.of(scheduleService.changeStatus(scheduleId,
                expectedRevision(request), AgentTaskScheduleStatusEnum.PAUSED));
    }

    @PostMapping("/{scheduleId}/resume")
    public DataResult<AgentTaskSchedule> resume(
            @PathVariable String scheduleId,
            @RequestBody AgentTaskScheduleLifecycleRequest request) {
        requireOwner(scheduleId);
        return DataResult.of(scheduleService.changeStatus(scheduleId,
                expectedRevision(request), AgentTaskScheduleStatusEnum.ACTIVE));
    }

    @PostMapping("/{scheduleId}/archive")
    public DataResult<AgentTaskSchedule> archive(
            @PathVariable String scheduleId,
            @RequestBody AgentTaskScheduleLifecycleRequest request) {
        requireOwner(scheduleId);
        return DataResult.of(scheduleService.changeStatus(scheduleId,
                expectedRevision(request), AgentTaskScheduleStatusEnum.ARCHIVED));
    }

    @PostMapping("/{scheduleId}/run-now")
    public DataResult<AgentTaskScheduleExecution> runNow(@PathVariable String scheduleId) {
        requireOwner(scheduleId);
        return DataResult.of(scheduleService.runNow(scheduleId));
    }

    private AgentTaskScheduleDetailResponse detail(AgentTaskSchedule schedule) {
        AgentTaskScheduleDetailResponse response = new AgentTaskScheduleDetailResponse();
        response.setSchedule(schedule);
        response.setExecutions(scheduleService.listExecutions(schedule.getId()));
        return response;
    }

    private AgentTaskSchedule requireOwner(String scheduleId) {
        AgentTaskSchedule schedule = scheduleService.get(scheduleId);
        if (!Objects.equals(schedule.getCreatedBy(), identityService.currentUserId())) {
            throw new IllegalArgumentException("agent task schedule is not accessible to the current user");
        }
        return schedule;
    }

    private long expectedRevision(AgentTaskScheduleLifecycleRequest request) {
        if (request == null || request.getExpectedRevision() == null
                || request.getExpectedRevision() <= 0) {
            throw new IllegalArgumentException("positive expected schedule revision is required");
        }
        return request.getExpectedRevision();
    }
}
