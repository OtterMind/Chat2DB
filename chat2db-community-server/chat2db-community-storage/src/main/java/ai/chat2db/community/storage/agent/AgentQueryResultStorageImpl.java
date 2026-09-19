package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.chart.DbAgentQueryResult;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.QueryData;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.domain.api.service.agent.IAgentOutputStorage;
import ai.chat2db.community.domain.api.service.agent.IAgentQueryResultStorage;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.exception.storage.StorageException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Query snapshots share their row file with model output and keep only a small index. */
@Component
public class AgentQueryResultStorageImpl implements IAgentQueryResultStorage {
    private final AgentStorageOwnership ownership;
    private final AgentSnapshotStorage<DbAgentQueryResult> legacy;
    private final IAgentOutputStorage outputs;
    private final AgentV2StoragePaths paths;
    private final StorageFileUtils files;
    private final ObjectMapper json = new ObjectMapper();

    public AgentQueryResultStorageImpl(AgentV2StoragePaths paths, StorageFileUtils files,
            AgentSessionStorage sessions, IAgentOutputStorage outputs) {
        this.paths = paths;
        this.files = files;
        this.outputs = outputs;
        ownership = new AgentStorageOwnership(sessions);
        legacy = new AgentSnapshotStorage<>(paths, files, "query-results", DbAgentQueryResult.class,
                DbAgentQueryResult::id, DbAgentQueryResult::sessionId, value -> Objects.requireNonNull(value.data()));
    }

    @Override
    public synchronized void create(DbAgentQueryResult result, Long userId) {
        ownership.require(result.sessionId(), userId);
        var file = paths.resourceFile(result.sessionId(), "query-results", result.id());
        files.createPrivateDirectory(file.getParent());
        files.verifyInsideRoot(paths.root(), file.getParent());
        files.rejectSymbolicLink(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new StorageException("Query result already exists");
        QueryData data = result.data();
        var header = new DbAgentQueryResult(result.id(), result.sessionId(), result.runId(), result.sql(), result.scope(),
                new QueryData(data.columns(), List.of(), data.valueEncoding(), data.durationMs(), data.cellWarnings(), data.affectedRows()),
                result.page(), result.warnings());
        var context = new AgentToolExecutionContext(result.sessionId(), result.runId(), "query-" + result.id(),
                userId, ignored -> { }, () -> true);
        boolean sourceComplete = data.cellWarnings() == null || data.cellWarnings().isEmpty();
        String sourceWarning = sourceComplete ? null : "Some database values are partial: "
                + Objects.toString(data.cellWarnings().get(0).reason(), "The database reader could not capture every value completely");
        AgentOutputReference output = outputs.save(context, "jsonl", stream -> {
            json.writeValue(stream, header);
            stream.write('\n');
            for (var row : data.rows()) {
                json.writeValue(stream, row);
                stream.write('\n');
            }
        }, sourceComplete, sourceWarning);
        if (!"file".equals(output.mode())) {
            throw new StorageException(Objects.toString(output.warning(), "Complete query output could not be saved"));
        }
        files.writeAtomically(file, jsonValue(Map.of("artifactId", output.artifactId())));
    }

    @Override
    public synchronized AgentOutputReference output(String sessionId, String resultId, Long userId) {
        if (!ownership.owns(sessionId, userId)) return null;
        String artifactId = artifactId(sessionId, resultId);
        return artifactId == null ? null : outputs.reference(sessionId, userId, artifactId);
    }

    @Override
    public synchronized DbAgentQueryResult get(String sessionId, String resultId, Long userId) {
        if (!ownership.owns(sessionId, userId)) return null;
        String artifactId = artifactId(sessionId, resultId);
        if (artifactId == null) return legacy.get(sessionId, resultId);
        if (!outputs.reference(sessionId, userId, artifactId).complete()) return null;
        try (var reader = new BufferedReader(new InputStreamReader(outputs.open(sessionId, userId, artifactId), StandardCharsets.UTF_8))) {
            DbAgentQueryResult header = json.readValue(reader.readLine(), DbAgentQueryResult.class);
            if (!sessionId.equals(header.sessionId()) || !resultId.equals(header.id())) throw new StorageException("Query result identity changed");
            List<List<String>> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) rows.add(json.readValue(line, new TypeReference<List<String>>() { }));
            QueryData data = header.data();
            return new DbAgentQueryResult(header.id(), header.sessionId(), header.runId(), header.sql(), header.scope(),
                    new QueryData(data.columns(), rows, data.valueEncoding(), data.durationMs(), data.cellWarnings(), data.affectedRows()),
                    header.page(), header.warnings());
        } catch (java.io.IOException error) {
            throw new StorageException("Cannot read saved query result", error);
        }
    }

    private String artifactId(String sessionId, String resultId) {
        var file = paths.resourceFile(sessionId, "query-results", resultId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        files.rejectSymbolicLink(file);
        files.verifyInsideRoot(paths.root(), file);
        try {
            var index = json.readTree(file.toFile());
            return index.hasNonNull("artifactId") ? index.get("artifactId").asText() : null;
        } catch (java.io.IOException error) {
            throw new StorageException("Cannot read query result index", error);
        }
    }

    private String jsonValue(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException error) { throw new StorageException("Cannot write query result index", error); }
    }
}
