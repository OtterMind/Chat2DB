package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;
import ai.chat2db.community.domain.api.service.agent.IAgentContextAssembler;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentContextAssemblerImpl implements IAgentContextAssembler {

    private static final int MAX_RECENT_CONTEXT = 12;
    private static final int MAX_RECENT_RUNS = 4;
    private static final int MAX_RUN_ANSWER_LENGTH = 8_000;
    private static final int MAX_TOOL_RESULT_LENGTH = 2_000;
    private static final int MAX_TOOL_EVENTS_PER_RUN = 8;

    private final IAgentControlStorage storage;

    public AgentContextAssemblerImpl(IAgentControlStorage storage) {
        this.storage = storage;
    }

    @Override
    public String assemble(AgentDefinition agent, AgentTask task, List<AgentRun> runHistory) {
        StringBuilder context = new StringBuilder("## Authorized Task Context\n");
        context.append("Task ID: ").append(task.getId()).append('\n');
        context.append("Agent: ").append(agent.getName()).append('\n');
        appendTaskDefinition(context, task);
        appendScopes(context, task.getDataScopeSnapshot());
        appendTaskContext(context, storage.listTaskContexts(task.getId()));
        appendRunHistory(context, runHistory, task.getCurrentRunId());
        return context.toString().trim();
    }

    private void appendTaskDefinition(StringBuilder context, AgentTask task) {
        context.append("\n### Task Definition\n");
        context.append("Title: ").append(task.getTitle()).append('\n');
        if (StringUtils.isNotBlank(task.getDescription())) {
            context.append("Goal and background: ").append(task.getDescription()).append('\n');
        }
        if (StringUtils.isNotBlank(task.getAcceptanceCriteria())) {
            context.append("Acceptance criteria: ").append(task.getAcceptanceCriteria()).append('\n');
        }
    }

    private void appendTaskContext(StringBuilder context, List<AgentTaskContext> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        List<AgentTaskContext> pinned = entries.stream()
                .filter(entry -> entry.getType() == AgentTaskContextTypeEnum.PINNED)
                .toList();
        AgentTaskContext currentRequest = entries.stream()
                .filter(entry -> entry.getType() == AgentTaskContextTypeEnum.COMMENT)
                .reduce((previous, current) -> current)
                .orElse(null);
        List<AgentTaskContext> recent = entries.stream()
                .filter(entry -> entry.getType() != AgentTaskContextTypeEnum.PINNED)
                .filter(entry -> currentRequest == null || !entry.getId().equals(currentRequest.getId()))
                .skip(Math.max(0, entries.stream()
                        .filter(entry -> entry.getType() != AgentTaskContextTypeEnum.PINNED)
                        .filter(entry -> currentRequest == null || !entry.getId().equals(currentRequest.getId()))
                        .count() - MAX_RECENT_CONTEXT))
                .toList();
        appendContextGroup(context, "Pinned Context", pinned);
        appendContextGroup(context, "Recent Collaboration Context", recent);
        if (currentRequest != null) {
            context.append("\n### Current User Request\n")
                    .append(currentRequest.getContent()).append('\n');
        }
    }

    private void appendContextGroup(StringBuilder context, String heading, List<AgentTaskContext> entries) {
        if (entries.isEmpty()) {
            return;
        }
        context.append("\n### ").append(heading).append('\n');
        for (AgentTaskContext entry : entries) {
            context.append("- [").append(entry.getType()).append("] ");
            if (StringUtils.isNotBlank(entry.getTitle())) {
                context.append(entry.getTitle()).append(": ");
            }
            if (StringUtils.isNotBlank(entry.getAttachmentName())) {
                context.append("attachment=").append(entry.getAttachmentName()).append("; ");
            }
            context.append(entry.getContent()).append('\n');
        }
    }

    private void appendScopes(StringBuilder context, List<AgentDataScope> scopes) {
        context.append("\n### Data Scope Snapshot\n");
        if (scopes == null || scopes.isEmpty()) {
            context.append("No database tools are authorized for this task.\n");
            return;
        }
        for (AgentDataScope scope : scopes) {
            context.append("- datasourceId=").append(scope.getDataSourceId());
            if (StringUtils.isNotBlank(scope.getDatabaseName())) {
                context.append(", database=").append(scope.getDatabaseName());
            }
            if (StringUtils.isNotBlank(scope.getSchemaName())) {
                context.append(", schema=").append(scope.getSchemaName());
            }
            if (scope.getTableNames() != null && !scope.getTableNames().isEmpty()) {
                context.append(", tables=").append(String.join(",", scope.getTableNames()));
            }
            context.append(", maxRows=").append(scope.getMaxRows()).append('\n');
        }
    }

    private void appendRunHistory(StringBuilder context, List<AgentRun> runs, String currentRunId) {
        List<AgentRun> previousRuns = (runs == null ? List.<AgentRun>of() : runs).stream()
                .filter(run -> !run.getId().equals(currentRunId))
                .filter(run -> StringUtils.isNotBlank(run.getResultSummary())
                        || StringUtils.isNotBlank(run.getFailureReason())
                        || hasRuntimeEvidence(run.getId()))
                .toList();
        if (previousRuns.isEmpty()) {
            return;
        }
        int fromIndex = Math.max(0, previousRuns.size() - MAX_RECENT_RUNS);
        context.append("\n### Previous Execution History\n");
        context.append("Treat completed answers and tool results below as existing task evidence. ")
                .append("Do not repeat a database query or other tool call unless the latest user message ")
                .append("explicitly requests a refresh/action, the previous result is insufficient, or current data is required.\n");
        for (AgentRun run : previousRuns.subList(fromIndex, previousRuns.size())) {
            context.append("\n#### Run ").append(run.getAttempt() == null ? run.getId() : run.getAttempt())
                    .append(" (status=").append(run.getStatus()).append(")\n");
            List<AgentRunEvent> events = storage.listRunEvents(run.getId());
            String answer = StringUtils.defaultIfBlank(run.getResultSummary(), answerFrom(events));
            if (StringUtils.isNotBlank(answer)) {
                context.append("Final answer:\n")
                        .append(truncate(answer, MAX_RUN_ANSWER_LENGTH)).append('\n');
            }
            if (StringUtils.isNotBlank(run.getFailureReason())) {
                context.append("Failure: ").append(run.getFailureReason()).append('\n');
            }
            appendToolEvidence(context, events);
        }
    }

    private boolean hasRuntimeEvidence(String runId) {
        return storage.listRunEvents(runId).stream().anyMatch(event ->
                event.getType() == AgentRuntimeEventTypeEnum.MESSAGE_DELTA
                        || event.getType() == AgentRuntimeEventTypeEnum.TOOL_CALL
                        || event.getType() == AgentRuntimeEventTypeEnum.TOOL_RESULT);
    }

    private String answerFrom(List<AgentRunEvent> events) {
        return events.stream()
                .filter(event -> event.getType() == AgentRuntimeEventTypeEnum.MESSAGE_DELTA)
                .map(AgentRunEvent::getContent)
                .filter(StringUtils::isNotEmpty)
                .reduce("", String::concat);
    }

    private void appendToolEvidence(StringBuilder context, List<AgentRunEvent> events) {
        List<AgentRunEvent> allToolEvents = events.stream().filter(event ->
                event.getType() == AgentRuntimeEventTypeEnum.TOOL_CALL
                        || event.getType() == AgentRuntimeEventTypeEnum.TOOL_RESULT).toList();
        int fromIndex = Math.max(0, allToolEvents.size() - MAX_TOOL_EVENTS_PER_RUN);
        List<AgentRunEvent> toolEvents = allToolEvents.subList(fromIndex, allToolEvents.size());
        if (toolEvents.isEmpty()) {
            return;
        }
        context.append("Tool evidence:\n");
        for (AgentRunEvent event : toolEvents) {
            String toolName = event.getPayload() == null ? null : String.valueOf(event.getPayload().get("name"));
            if ("null".equals(toolName)) {
                toolName = null;
            }
            context.append("- ").append(event.getType());
            if (StringUtils.isNotBlank(toolName)) {
                context.append(" ").append(toolName);
            } else if (event.getType() == AgentRuntimeEventTypeEnum.TOOL_CALL
                    && StringUtils.isNotBlank(event.getContent())) {
                context.append(" ").append(event.getContent());
            }
            if (event.getType() == AgentRuntimeEventTypeEnum.TOOL_RESULT
                    && StringUtils.isNotBlank(event.getContent())) {
                context.append(": ").append(truncate(event.getContent(), MAX_TOOL_RESULT_LENGTH));
            }
            context.append('\n');
        }
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n[truncated]";
    }
}
