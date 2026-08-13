package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
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
                .filter(entry -> entry.getType() == ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum.PINNED)
                .toList();
        List<AgentTaskContext> recent = entries.stream()
                .filter(entry -> entry.getType() != ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum.PINNED)
                .skip(Math.max(0, entries.stream()
                        .filter(entry -> entry.getType() != ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum.PINNED)
                        .count() - MAX_RECENT_CONTEXT))
                .toList();
        appendContextGroup(context, "Pinned Context", pinned);
        appendContextGroup(context, "Recent Collaboration Context", recent);
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
                        || StringUtils.isNotBlank(run.getFailureReason()))
                .toList();
        if (previousRuns.isEmpty()) {
            return;
        }
        context.append("\n### Previous Run Summaries\n");
        for (AgentRun run : previousRuns) {
            context.append("- run=").append(run.getId()).append(", status=").append(run.getStatus());
            if (StringUtils.isNotBlank(run.getResultSummary())) {
                context.append(", summary=").append(run.getResultSummary());
            }
            if (StringUtils.isNotBlank(run.getFailureReason())) {
                context.append(", failure=").append(run.getFailureReason());
            }
            context.append('\n');
        }
    }
}
