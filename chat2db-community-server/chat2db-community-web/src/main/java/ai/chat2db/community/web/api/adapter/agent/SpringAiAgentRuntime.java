package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRunHandle;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCapabilities;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeResumeRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.domain.api.service.agent.runtime.AgentEventSink;
import ai.chat2db.community.domain.api.service.agent.runtime.AgentRuntime;
import ai.chat2db.community.web.api.adapter.ai.AiChatStreamAdapter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SpringAiAgentRuntime implements AgentRuntime {

    private final AiChatStreamAdapter chatStreamAdapter;
    private final Map<String, Disposable> activeRuns = new ConcurrentHashMap<>();

    public SpringAiAgentRuntime(AiChatStreamAdapter chatStreamAdapter) {
        this.chatStreamAdapter = chatStreamAdapter;
    }

    @Override
    public AgentRuntimeTypeEnum type() {
        return AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI;
    }

    @Override
    public AgentRuntimeCapabilities capabilities() {
        AgentRuntimeCapabilities capabilities = new AgentRuntimeCapabilities();
        capabilities.setStreaming(true);
        capabilities.setToolCalling(true);
        capabilities.setCancellation(true);
        capabilities.setApprovalResume(true);
        capabilities.setSessionResume(false);
        capabilities.setExternalProcess(false);
        capabilities.setRuntimeVersion("spring-ai-1.1");
        return capabilities;
    }

    @Override
    public AgentRunHandle start(AgentRuntimeStartRequest request, AgentEventSink eventSink) {
        if (request == null || request.getRun() == null || StringUtils.isBlank(request.getRun().getId())) {
            throw new IllegalArgumentException("run is required");
        }
        if (request.getRun().getRuntimeType() != type()) {
            throw new IllegalArgumentException("run runtime type does not match embedded Spring AI runtime");
        }
        if (eventSink == null) {
            throw new IllegalArgumentException("agent event sink is required");
        }
        String runId = request.getRun().getId();
        if (activeRuns.containsKey(runId)) {
            throw new IllegalStateException("agent run is already active: " + runId);
        }
        AtomicReference<Disposable> execution = new AtomicReference<>();
        Disposable disposable = chatStreamAdapter.streamAgent(request, event -> {
            eventSink.emit(event);
            if (event.getType() == ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum.ERROR
                    || (event.getType() == ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum.STATUS
                    && "COMPLETED".equals(event.getContent()))) {
                Disposable currentExecution = execution.get();
                if (currentExecution != null) {
                    activeRuns.remove(runId, currentExecution);
                }
            }
        });
        execution.set(disposable);
        activeRuns.put(runId, disposable);
        if (disposable.isDisposed()) {
            activeRuns.remove(runId, disposable);
        }

        AgentRunHandle handle = new AgentRunHandle();
        handle.setRunId(runId);
        handle.setRuntimeExecutionId(runId);
        handle.setAcceptedAt(new Date());
        return handle;
    }

    @Override
    public void resume(AgentRuntimeResumeRequest request, AgentEventSink eventSink) {
        if (request == null || StringUtils.isBlank(request.getRunId()) || request.getStartRequest() == null
                || request.getStartRequest().getRun() == null
                || !request.getRunId().equals(request.getStartRequest().getRun().getId())) {
            throw new IllegalArgumentException("matching run and start request are required for approval resume");
        }
        Disposable previous = activeRuns.remove(request.getRunId());
        if (previous != null) {
            previous.dispose();
        }
        start(request.getStartRequest(), eventSink);
    }

    @Override
    public void cancel(String runId) {
        Disposable disposable = activeRuns.remove(runId);
        if (disposable != null) {
            disposable.dispose();
        }
    }
}
