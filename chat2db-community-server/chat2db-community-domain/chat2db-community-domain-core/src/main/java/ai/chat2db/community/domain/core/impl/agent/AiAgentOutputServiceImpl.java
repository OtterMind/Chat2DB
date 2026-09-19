package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.output.*;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import ai.chat2db.community.domain.api.service.agent.IAgentOutputStorage;
import ai.chat2db.community.domain.api.service.agent.IAgentQueryResultStorage;
import ai.chat2db.community.domain.api.service.agent.IAiAgentOutputService;
import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import ai.chat2db.community.tools.model.agent.tool.AgentPresentedToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** The output policy belongs to Agent V2; V1 tool execution never enters this service. */
@Service
public class AiAgentOutputServiceImpl implements IAiAgentOutputService {
    private final IAgentOutputStorage storage;
    private final IAgentQueryResultStorage queries;
    private final ObjectMapper mapper;
    private final int inlineBytes;
    private final int previewBytes;

    @Autowired
    public AiAgentOutputServiceImpl(IAgentOutputStorage storage, IAgentQueryResultStorage queries, ObjectMapper mapper,
            @Value("${chat2db.agent.v2.outputs.inline-bytes:32768}") int inlineBytes,
            @Value("${chat2db.agent.v2.outputs.preview-bytes:8192}") int previewBytes) {
        if (inlineBytes < 1024 || previewBytes < 256 || previewBytes >= inlineBytes) {
            throw new IllegalArgumentException("Output budgets must satisfy 256 <= preview < inline and inline >= 1024");
        }
        this.storage = storage;
        this.queries = queries;
        this.mapper = mapper;
        this.inlineBytes = inlineBytes;
        this.previewBytes = previewBytes;
    }

    @Override
    public IAgentToolResult<?> present(IAgentToolResult<?> result, AgentToolExecutionContext context) {
        if (result == null || fitsInline(result)) return result;
        Map<String, Object> fields = fields(result);
        if (fields.get("output") != null) return result;
        if (result.data() instanceof DbAgentDatabaseResponse.SqlExecutionData execution) {
            return presentSql(fields, execution, context);
        }
        AgentOutputReference reference = storage.save(context, "json", output -> mapper.writerWithDefaultPrettyPrinter()
                .writeValue(output, result));
        Map<String, Object> preview = preview(fields);
        preview.put("output", reference);
        return new AgentPresentedToolResult(preview);
    }

    private IAgentToolResult<?> presentSql(Map<String, Object> fields, DbAgentDatabaseResponse.SqlExecutionData execution,
            AgentToolExecutionContext context) {
        List<AgentOutputReference> references = new ArrayList<>();
        for (DbAgentDatabaseResponse.SqlResult statement : execution.results()) {
            AgentOutputReference reference = null;
            if (statement.resultId() != null) {
                try { reference = queries.output(context.sessionId(), statement.resultId(), context.userId()); }
                catch (RuntimeException ignored) {
                    // Preserve the SQL outcome if its snapshot became unavailable; save this returned value below.
                }
            }
            if (reference == null) {
                AgentToolExecutionContext statementContext = new AgentToolExecutionContext(context.sessionId(),
                        context.runId(), context.toolCallId() + "_statement_" + statement.statementIndex(), context.userId(),
                        context.eventSink(), context.active());
                reference = storage.save(statementContext, "json", output -> mapper.writerWithDefaultPrettyPrinter()
                        .writeValue(output, statement));
            }
            if (statement.data() != null && statement.data().cellWarnings() != null
                    && !statement.data().cellWarnings().isEmpty()) {
                reference = new AgentOutputReference(reference.mode(), reference.artifactId(), reference.path(),
                        reference.format(), reference.sizeBytes(), false, true,
                        "Some values were shortened by the database reader; the file contains all values actually returned");
            }
            references.add(reference);
        }
        Map<String, Object> header = new LinkedHashMap<>(fields);
        header.remove("data");
        Budget budget = new Budget(previewBytes);
        Map<String, Object> preview = previewFields(header, budget);
        // Keep every statement's identity, outcome and output reference, including statements
        // whose preview rows no longer fit the shared budget.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("statementCount", execution.statementCount());
        data.put("readOnly", execution.readOnly());
        List<Map<String, Object>> statements = new ArrayList<>();
        for (int i = 0; i < execution.results().size(); i++) {
            var statement = execution.results().get(i);
            Map<String, Object> source = fields(statement);
            Map<String, Object> item = previewFields(source, budget);
            item.put("statementIndex", statement.statementIndex());
            item.put("success", statement.success());
            item.put("resultId", statement.resultId());
            item.put("output", references.get(i));
            if ("jsonl".equals(references.get(i).format())) {
                item.put("outputLayout", "First line: query metadata and column order; following lines: row arrays, with SQL NULL as JSON null");
            }
            statements.add(item);
        }
        data.put("results", statements);
        preview.put("data", data);
        if (references.size() == 1) preview.put("output", references.get(0));
        return new AgentPresentedToolResult(preview);
    }

    private boolean fitsInline(Object value) {
        try {
            mapper.writeValue(new OutputStream() {
                private int size;
                @Override public void write(int value) throws IOException { add(1); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { add(length); }
                private void add(int length) throws IOException {
                    if ((long) size + length > inlineBytes) throw new InlineLimitExceeded();
                    size += length;
                }
            }, value);
            return true;
        } catch (IOException exception) {
            if (exception instanceof InlineLimitExceeded || exception.getCause() instanceof InlineLimitExceeded) return false;
            throw new IllegalArgumentException("Tool output could not be serialized", exception);
        }
    }

    private Map<String, Object> preview(Map<String, Object> fields) {
        return previewFields(fields, new Budget(previewBytes));
    }

    private Map<String, Object> previewFields(Map<String, Object> fields, Budget budget) {
        Map<String, Object> result = new LinkedHashMap<>();
        // Outcomes and continuation metadata are more useful than extra preview rows.
        for (String name : List.of("ok", "success", "statementIndex", "resultId", "scope", "page", "error", "nextAction", "warnings")) {
            if (fields.containsKey(name)) {
                budget.remaining -= name.length() + 3;
                result.put(name, copy(fields.get(name), budget));
            }
        }
        for (var entry : fields.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                budget.remaining -= entry.getKey().length() + 3;
                result.put(entry.getKey(), copy(entry.getValue(), budget));
            }
        }
        return result;
    }

    private Object copy(Object value, Budget budget) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            budget.remaining -= 12;
            return value;
        }
        if (value instanceof String text) return budget.text(text);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            budget.remaining -= 2;
            for (Object item : list) {
                if (budget.remaining < 16) break;
                budget.remaining--;
                result.add(copy(item, budget));
            }
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            budget.remaining -= 2;
            for (var entry : map.entrySet()) {
                if (budget.remaining < 16) break;
                String key = String.valueOf(entry.getKey());
                int cost = key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 3;
                if (cost >= budget.remaining) break;
                budget.remaining -= cost;
                result.put(key, copy(entry.getValue(), budget));
            }
            return result;
        }
        if (value instanceof Enum<?> enumeration) return budget.text(enumeration.name());
        return copy(fields(value), budget);
    }

    /** Read bean properties without cloning lists or giant strings into an intermediate JSON tree. */
    private Map<String, Object> fields(Object value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> fields.put(String.valueOf(key), item));
            return fields;
        }
        for (var property : mapper.getSerializationConfig().introspect(mapper.constructType(value.getClass())).findProperties()) {
            var accessor = property.getAccessor();
            if (accessor != null) {
                accessor.fixAccess(true);
                fields.put(property.getName(), accessor.getValue(value));
            }
        }
        return fields;
    }

    @Override public AgentOutputUpload begin(AgentToolExecutionContext context, String format) { return storage.begin(context, format); }
    @Override public void append(AgentToolExecutionContext context, String uploadId, String base64) {
        if (base64 == null || base64.length() > 349528) throw new IllegalArgumentException("Output chunk is too large");
        storage.append(context, uploadId, Base64.getDecoder().decode(base64));
    }
    @Override public AgentOutputReference finish(AgentToolExecutionContext context, String uploadId, boolean complete, String warning) {
        return storage.finish(context, uploadId, complete, warning);
    }
    @Override public AgentOutputReference reference(String sessionId, Long userId, String pathOrArtifactId) {
        return storage.reference(sessionId, userId, pathOrArtifactId);
    }
    @Override public AgentOutputRead read(String sessionId, Long userId, String pathOrArtifactId, String cursor, Integer offset, Integer limit) {
        return storage.read(sessionId, userId, pathOrArtifactId, cursor, offset, limit);
    }
    @Override public AgentOutputSearch search(String sessionId, Long userId, String pathOrArtifactId, String pattern,
            boolean literal, boolean ignoreCase, String cursor, Integer limit) {
        return storage.search(sessionId, userId, pathOrArtifactId, pattern, literal, ignoreCase, cursor, limit);
    }
    @Override public void download(String sessionId, Long userId, String pathOrArtifactId, OutputStream output) throws IOException {
        try (InputStream input = storage.open(sessionId, userId, pathOrArtifactId)) { input.transferTo(output); }
    }
    @Override public AgentOutputRead readFile(Path path, String cursor, Integer offset, Integer limit) {
        return storage.readFile(path, cursor, offset, limit);
    }
    @Override public AgentOutputSearch searchFile(Path path, String pattern, boolean literal, boolean ignoreCase, String cursor, Integer limit) {
        return storage.searchFile(path, pattern, literal, ignoreCase, cursor, limit);
    }
    @Override public Path managedRoot() { return storage.managedRoot(); }

    private static final class InlineLimitExceeded extends IOException { }
    private final class Budget {
        private int remaining;
        Budget(int remaining) { this.remaining = remaining; }
        String text(String value) {
            int max = Math.max(0, remaining - 5);
            int end = Math.min(value.length(), max);
            if (end > 0 && end < value.length() && Character.isLowSurrogate(value.charAt(end))) end--;
            String result = value.substring(0, end);
            try {
                while (mapper.writeValueAsBytes(result).length > max && !result.isEmpty()) {
                    end = Math.max(0, end * 3 / 4);
                    if (end > 0 && end < value.length() && Character.isLowSurrogate(value.charAt(end))) end--;
                    result = value.substring(0, end);
                }
                boolean truncated = end < value.length();
                result += truncated && max >= 5 ? "…" : "";
                remaining -= mapper.writeValueAsBytes(result).length;
                return result;
            } catch (IOException exception) { throw new IllegalArgumentException(exception); }
        }
    }
}
