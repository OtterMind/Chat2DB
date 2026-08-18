package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentTaskSchedule;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleExecution;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskScheduleCreateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskScheduleService;
import ai.chat2db.community.web.api.model.request.agent.AgentTaskScheduleCronPreviewRequest;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentTaskScheduleControllerTest {

    @Test
    void createBindsCurrentIdentityAndReturnsExecutionDetail() {
        AtomicReference<AgentTaskScheduleCreateRequest> captured = new AtomicReference<>();
        AgentTaskSchedule mine = schedule("schedule-1", 7L);
        IAgentTaskScheduleService service = proxy(IAgentTaskScheduleService.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "create" -> {
                        captured.set((AgentTaskScheduleCreateRequest) args[0]);
                        yield mine;
                    }
                    case "listExecutions" -> List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AgentTaskScheduleController controller = new AgentTaskScheduleController(service, () -> 7L);
        AgentTaskScheduleCreateRequest request = new AgentTaskScheduleCreateRequest();
        request.setCreatedBy(999L);

        assertEquals("schedule-1", controller.create(request).getData().getSchedule().getId());
        assertEquals(7L, captured.get().getCreatedBy());
    }

    @Test
    void rejectsReadingOrMutatingAnotherUsersSchedule() {
        AgentTaskSchedule other = schedule("other", 8L);
        IAgentTaskScheduleService service = proxy(IAgentTaskScheduleService.class, (proxy, method, args) -> {
            if ("get".equals(method.getName())) return other;
            throw new UnsupportedOperationException(method.getName());
        });
        AgentTaskScheduleController controller = new AgentTaskScheduleController(service, () -> 7L);

        assertThrows(IllegalArgumentException.class, () -> controller.get("other"));
        assertThrows(IllegalArgumentException.class, () -> controller.runNow("other"));
    }

    @Test
    void cronPreviewUsesThreeOccurrencesAndPreservesTimezone() {
        AtomicReference<String> timezone = new AtomicReference<>();
        AtomicReference<Integer> count = new AtomicReference<>();
        Date next = Date.from(Instant.parse("2026-08-18T01:00:00Z"));
        IAgentTaskScheduleService service = proxy(IAgentTaskScheduleService.class, (proxy, method, args) -> {
            if ("preview".equals(method.getName())) {
                timezone.set((String) args[1]);
                count.set((Integer) args[2]);
                return List.of(next);
            }
            throw new UnsupportedOperationException(method.getName());
        });
        AgentTaskScheduleController controller = new AgentTaskScheduleController(service, () -> 7L);
        AgentTaskScheduleCronPreviewRequest request = new AgentTaskScheduleCronPreviewRequest();
        request.setExpression("0 9 * * *");
        request.setTimezone("Asia/Shanghai");

        assertEquals(List.of(next), controller.preview(request).getData().getNextRuns());
        assertEquals("Asia/Shanghai", timezone.get());
        assertEquals(3, count.get());
    }

    @Test
    void executionResponseNeverSerializesInternalLeaseToken() throws Exception {
        AgentTaskScheduleExecution execution = new AgentTaskScheduleExecution();
        execution.setId("execution-1");
        execution.setLeaseToken("lease-secret");

        assertFalse(JSON.toJSONString(execution).contains("lease-secret"));
        assertFalse(new ObjectMapper().writeValueAsString(execution).contains("lease-secret"));
    }

    private AgentTaskSchedule schedule(String id, Long createdBy) {
        AgentTaskSchedule schedule = new AgentTaskSchedule();
        schedule.setId(id);
        schedule.setCreatedBy(createdBy);
        return schedule;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler);
    }
}
