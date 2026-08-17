package ai.chat2db.community.runtime.provider;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import org.apache.commons.lang3.StringUtils;

public final class RuntimePromptBuilder {

    public String build(AgentRuntimeStartRequest request) {
        if (request == null || request.getTask() == null || request.getAgent() == null) {
            throw new IllegalArgumentException("External runtime requires immutable Agent and Task snapshots");
        }
        AgentTask task = request.getTask();
        AgentDefinition agent = request.getAgent();
        StringBuilder prompt = new StringBuilder(4096);
        prompt.append("# Chat2DB Agent Task\n\n");
        append(prompt, "Agent", agent.getName());
        append(prompt, "Task", task.getTitle());
        append(prompt, "Description", task.getDescription());
        append(prompt, "Acceptance Criteria", task.getAcceptanceCriteria());
        append(prompt, "Current User Input", request.getCurrentInput());
        append(prompt, "Immutable Control-Plane Context", request.getAssembledContext());
        prompt.append("\n## Runtime Boundary\n\n")
                .append("Chat2DB is the source of truth for task state, permissions, approvals, tools, and artifacts. ")
                .append("Do not infer database credentials or bypass the Chat2DB Tool Gateway. ")
                .append("Work only inside the supplied runtime workspace. ")
                .append("Return the final deliverable as Markdown in your final response. ")
                .append("For additional explicit artifacts, write a JSON array of Artifact Manifest objects to ")
                .append(".chat2db-artifacts.json in the workspace. Use inline UTF-8 content for REPORT/CHART/")
                .append("DATA_TABLE/METRIC and contentBase64 for FILE; never put local paths in the manifest. ")
                .append("Each object uses artifactId, type, title, mimeType, size, sha256, content or ")
                .append("contentBase64, optional fileName, and evidence entries containing toolAttemptId.\n");
        return prompt.toString().trim();
    }

    private void append(StringBuilder target, String heading, String content) {
        if (StringUtils.isNotBlank(content)) {
            target.append("## ").append(heading).append("\n\n")
                    .append(content.trim()).append("\n\n");
        }
    }
}
