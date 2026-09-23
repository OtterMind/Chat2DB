package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.output.*;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

public interface IAiAgentOutputService {
    IAgentToolResult<?> present(IAgentToolResult<?> result, AgentToolExecutionContext context);
    AgentOutputUpload begin(AgentToolExecutionContext context, String format);
    void append(AgentToolExecutionContext context, String uploadId, String base64);
    AgentOutputReference finish(AgentToolExecutionContext context, String uploadId, boolean complete, String warning);
    AgentOutputReference reference(String sessionId, Long userId, String pathOrArtifactId);
    AgentOutputRead read(String sessionId, Long userId, String pathOrArtifactId, String cursor, Integer offset, Integer limit);
    AgentOutputSearch search(String sessionId, Long userId, String pathOrArtifactId, String pattern,
            boolean literal, boolean ignoreCase, String cursor, Integer limit);
    void download(String sessionId, Long userId, String pathOrArtifactId, OutputStream output) throws IOException;
    AgentOutputRead readFile(Path path, String cursor, Integer offset, Integer limit);
    AgentOutputSearch searchFile(Path path, String pattern, boolean literal, boolean ignoreCase, String cursor, Integer limit);
    Path managedRoot();
}
