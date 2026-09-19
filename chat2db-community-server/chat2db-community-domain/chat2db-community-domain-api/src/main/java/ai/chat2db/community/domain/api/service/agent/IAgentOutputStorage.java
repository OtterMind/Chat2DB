package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.output.*;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/** Persistent V2 output boundary. Callers must never import an arbitrary model-supplied file. */
public interface IAgentOutputStorage {
    @FunctionalInterface
    interface OutputWriter { void write(OutputStream output) throws IOException; }
    default AgentOutputReference save(AgentToolExecutionContext context, String format, OutputWriter writer) {
        return save(context, format, writer, true, null);
    }
    AgentOutputReference save(AgentToolExecutionContext context, String format, OutputWriter writer,
            boolean complete, String warning);
    AgentOutputUpload begin(AgentToolExecutionContext context, String format);
    void append(AgentToolExecutionContext context, String uploadId, byte[] bytes);
    AgentOutputReference finish(AgentToolExecutionContext context, String uploadId, boolean complete, String warning);
    AgentOutputReference reference(String sessionId, Long userId, String pathOrArtifactId);
    InputStream open(String sessionId, Long userId, String pathOrArtifactId) throws IOException;
    AgentOutputRead read(String sessionId, Long userId, String pathOrArtifactId, String cursor, Integer offset, Integer limit);
    AgentOutputSearch search(String sessionId, Long userId, String pathOrArtifactId, String pattern,
            boolean literal, boolean ignoreCase, String cursor, Integer limit);
    /** For paths already authorized by the native tool access policy. */
    AgentOutputRead readFile(Path path, String cursor, Integer offset, Integer limit);
    AgentOutputSearch searchFile(Path path, String pattern, boolean literal, boolean ignoreCase, String cursor, Integer limit);
    Path managedRoot();
}
