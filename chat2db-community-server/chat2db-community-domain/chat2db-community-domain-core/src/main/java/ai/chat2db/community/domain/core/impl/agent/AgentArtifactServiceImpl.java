package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactEvidence;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactVersion;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.enums.agent.AgentToolAttemptStatusEnum;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactVersionCreateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentArtifactServiceImpl implements IAgentArtifactService {

    private static final Pattern CHART_BLOCK = Pattern.compile("(?s)```chart\\s*(\\{.*?})\\s*```");
    private static final Set<String> CHART_TYPES = Set.of(
            "Column", "Bar", "Line", "AreaLine", "Pie", "RingPie", "RosePie",
            "Funnel", "Scatter", "Statistics", "Combo");

    private final IAgentControlStorage storage;

    public AgentArtifactServiceImpl(IAgentControlStorage storage) {
        this.storage = storage;
    }

    @Override
    public AgentArtifactDetail create(AgentArtifactCreateRequest request) {
        validateCreate(request);
        AgentTask task = requireTask(request.getTaskId());
        requireRunBelongsToTask(request.getCreatedByRunId(), task.getId());
        if (StringUtils.isNotBlank(request.getCreatedByRunId())) {
            AgentArtifact existing = storage.getArtifactByRunAndType(
                    task.getId(), request.getCreatedByRunId(), request.getType());
            if (existing != null) {
                return get(existing.getId());
            }
        }

        Date now = new Date();
        AgentArtifact artifact = new AgentArtifact();
        artifact.setId(UUID.randomUUID().toString());
        artifact.setTaskId(task.getId());
        artifact.setType(request.getType());
        artifact.setTitle(StringUtils.defaultIfBlank(request.getTitle(), defaultTitle(request.getType())).trim());
        artifact.setStatus(request.getStatus() == null ? AgentArtifactStatusEnum.DRAFT : request.getStatus());
        artifact.setCurrentVersion(1);
        artifact.setCreatedByRunId(StringUtils.trimToNull(request.getCreatedByRunId()));
        artifact.setCreatedBy(request.getCreatedBy());
        artifact.setGmtCreate(now);
        artifact.setGmtModified(now);
        artifact.setRevision(1L);

        AgentArtifactVersion version = version(artifact.getId(), 1,
                request.getContentMode(), request.getContent(), request.getCreatedByRunId(), null, now);
        List<AgentArtifactEvidence> evidence = evidence(
                request.getEvidence(), artifact.getId(), 1, task.getId(), now);
        return storage.createArtifact(artifact, version, evidence);
    }

    @Override
    public AgentArtifactDetail addVersion(AgentArtifactVersionCreateRequest request) {
        if (request == null || StringUtils.isBlank(request.getArtifactId())) {
            throw new IllegalArgumentException("artifact version request and artifact id are required");
        }
        if (request.getExpectedRevision() == null || request.getExpectedRevision() <= 0) {
            throw new IllegalArgumentException("positive expected artifact revision is required");
        }
        requireContent(request.getContent());
        AgentArtifact current = requireArtifact(request.getArtifactId());
        if (!request.getExpectedRevision().equals(current.getRevision())) {
            throw new IllegalStateException("artifact revision has changed; refresh before creating a version");
        }
        requireRunBelongsToTask(request.getCreatedByRunId(), current.getTaskId());

        Date now = new Date();
        AgentArtifact updated = copy(current);
        int nextVersion = current.getCurrentVersion() + 1;
        updated.setCurrentVersion(nextVersion);
        updated.setGmtModified(now);
        updated.setRevision(current.getRevision() + 1);
        AgentArtifactVersion version = version(current.getId(), nextVersion,
                request.getContentMode(), request.getContent(), request.getCreatedByRunId(),
                current.getCurrentVersion(), now);
        List<AgentArtifactEvidence> evidence = evidence(
                request.getEvidence(), current.getId(), nextVersion, current.getTaskId(), now);
        return storage.appendArtifactVersion(updated, version, evidence, request.getExpectedRevision());
    }

    @Override
    public AgentArtifactDetail get(String artifactId) {
        AgentArtifact artifact = requireArtifact(artifactId);
        AgentArtifactDetail detail = new AgentArtifactDetail();
        detail.setArtifact(artifact);
        detail.setVersions(storage.listArtifactVersions(artifactId));
        detail.setEvidence(storage.listArtifactEvidence(artifactId));
        return detail;
    }

    @Override
    public List<AgentArtifact> listByTask(String taskId) {
        requireTask(taskId);
        return storage.listArtifactsByTask(taskId);
    }

    @Override
    public List<AgentArtifactDetail> extractStructuredArtifacts(String taskId, String runId,
                                                                Long createdBy, String markdown) {
        requireTask(taskId);
        requireRunBelongsToTask(runId, taskId);
        if (StringUtils.isBlank(markdown)) {
            return List.of();
        }
        List<AgentArtifactEvidence> evidence = successfulSqlEvidence(runId);
        if (evidence.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> charts = chartSpecs(markdown);
        if (charts.isEmpty()) {
            return List.of();
        }

        List<AgentArtifactDetail> result = new ArrayList<>();
        AgentArtifactCreateRequest chartRequest = structuredRequest(
                taskId, runId, createdBy, AgentArtifactTypeEnum.CHART, "Analysis Charts",
                Map.of("artifactType", AgentArtifactTypeEnum.CHART.name(), "charts", charts), evidence);
        result.add(create(chartRequest));

        List<Map<String, Object>> tables = new ArrayList<>();
        for (int index = 0; index < charts.size(); index++) {
            Map<String, Object> chart = charts.get(index);
            Object data = chart.get("data");
            if (!(data instanceof List<?> rows) || rows.isEmpty()) {
                continue;
            }
            LinkedHashSet<String> columns = new LinkedHashSet<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    map.keySet().stream().map(String::valueOf).forEach(columns::add);
                }
            }
            if (!columns.isEmpty()) {
                String chartTitle = chart.get("title") instanceof String value ? value : null;
                tables.add(Map.of(
                        "title", StringUtils.defaultIfBlank(chartTitle,
                                "Chart Data " + (index + 1)),
                        "columns", new ArrayList<>(columns),
                        "rows", rows));
            }
        }
        if (!tables.isEmpty()) {
            AgentArtifactCreateRequest tableRequest = structuredRequest(
                    taskId, runId, createdBy, AgentArtifactTypeEnum.DATA_TABLE, "Analysis Data Tables",
                    Map.of("artifactType", AgentArtifactTypeEnum.DATA_TABLE.name(), "tables", tables), evidence);
            result.add(create(tableRequest));
        }
        return result;
    }

    @Override
    public boolean satisfiesOutputContract(AgentDefinition agent, String taskId) {
        List<AgentArtifact> artifacts = listByTask(taskId).stream()
                .filter(artifact -> artifact.getStatus() == AgentArtifactStatusEnum.READY)
                .toList();
        if (StringUtils.isBlank(agent.getOutputContract())) {
            return artifacts.stream().anyMatch(artifact -> artifact.getType() == AgentArtifactTypeEnum.REPORT);
        }
        Map<String, Object> contract;
        try {
            contract = JSON.parseObject(agent.getOutputContract(), Map.class);
        } catch (RuntimeException exception) {
            return false;
        }
        if (!requiredArtifactCountsSatisfied(contract.get("requiredArtifacts"), artifacts)) {
            return false;
        }
        return requiredSectionsSatisfied(contract.get("requiredSections"), artifacts);
    }

    private boolean requiredArtifactCountsSatisfied(Object value, List<AgentArtifact> artifacts) {
        if (!(value instanceof List<?> requirements)) {
            return artifacts.stream().anyMatch(artifact -> artifact.getType() == AgentArtifactTypeEnum.REPORT);
        }
        for (Object requirement : requirements) {
            if (!(requirement instanceof Map<?, ?> map) || map.get("type") == null) {
                return false;
            }
            AgentArtifactTypeEnum type;
            try {
                type = AgentArtifactTypeEnum.valueOf(String.valueOf(map.get("type")).toUpperCase());
            } catch (IllegalArgumentException exception) {
                return false;
            }
            int minimum = map.get("min") instanceof Number number ? number.intValue() : 1;
            long count = artifacts.stream().filter(artifact -> artifact.getType() == type).count();
            if (minimum <= 0 || count < minimum) {
                return false;
            }
        }
        return true;
    }

    private AgentArtifactCreateRequest structuredRequest(String taskId, String runId, Long createdBy,
                                                         AgentArtifactTypeEnum type, String title,
                                                         Map<String, Object> content,
                                                         List<AgentArtifactEvidence> evidence) {
        AgentArtifactCreateRequest request = new AgentArtifactCreateRequest();
        request.setTaskId(taskId);
        request.setType(type);
        request.setTitle(title);
        request.setStatus(AgentArtifactStatusEnum.READY);
        request.setContentMode(AgentArtifactContentModeEnum.SNAPSHOT);
        request.setContent(content);
        request.setCreatedByRunId(runId);
        request.setCreatedBy(createdBy);
        request.setEvidence(evidence);
        return request;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chartSpecs(String markdown) {
        List<Map<String, Object>> charts = new ArrayList<>();
        Matcher matcher = CHART_BLOCK.matcher(markdown);
        while (matcher.find()) {
            try {
                Map<String, Object> chart = JSON.parseObject(matcher.group(1), LinkedHashMap.class);
                if (validChart(chart)) {
                    charts.add(chart);
                }
            } catch (RuntimeException ignored) {
                // Invalid model output remains visible in the report but is not promoted to an Artifact.
            }
        }
        return charts;
    }

    private boolean validChart(Map<String, Object> chart) {
        if (chart == null || !CHART_TYPES.contains(String.valueOf(chart.get("chartType")))) {
            return false;
        }
        Object data = chart.get("data");
        if (!(data instanceof List<?> rows) || rows.isEmpty() || rows.size() > 500) {
            return false;
        }
        return rows.stream().allMatch(row -> row instanceof Map<?, ?> map
                && !map.isEmpty()
                && map.values().stream().noneMatch(value -> value instanceof Map<?, ?> || value instanceof List<?>));
    }

    private List<AgentArtifactEvidence> successfulSqlEvidence(String runId) {
        List<AgentArtifactEvidence> evidence = new ArrayList<>();
        for (AgentToolAttempt attempt : storage.listToolAttempts(runId)) {
            if (attempt.getStatus() != AgentToolAttemptStatusEnum.SUCCEEDED) {
                continue;
            }
            AgentSqlProposal proposal = storage.getSqlProposal(attempt.getProposalId());
            if (proposal == null) {
                continue;
            }
            AgentArtifactEvidence item = new AgentArtifactEvidence();
            item.setRunId(runId);
            item.setToolAttemptId(attempt.getId());
            item.setDataSourceId(proposal.getDataSourceId());
            item.setDatabaseName(proposal.getDatabaseName());
            item.setSchemaName(proposal.getSchemaName());
            item.setSqlSnapshot(proposal.getSqlSnapshot());
            item.setSqlHash(proposal.getSqlHash());
            item.setExecutedAt(attempt.getCompletedAt());
            item.setResultSnapshotId(attempt.getId());
            evidence.add(item);
        }
        return evidence;
    }

    private boolean requiredSectionsSatisfied(Object value, List<AgentArtifact> artifacts) {
        if (!(value instanceof List<?> required) || required.isEmpty()) {
            return true;
        }
        Set<String> available = new LinkedHashSet<>();
        for (AgentArtifact artifact : artifacts) {
            if (artifact.getType() != AgentArtifactTypeEnum.REPORT) {
                continue;
            }
            AgentArtifactDetail detail = get(artifact.getId());
            detail.getVersions().stream()
                    .filter(version -> version.getVersion().equals(artifact.getCurrentVersion()))
                    .findFirst()
                    .ifPresent(version -> collectBlockTypes(version.getContent().get("blocks"), available));
        }
        return required.stream().map(String::valueOf).map(String::toLowerCase).allMatch(available::contains);
    }

    private void collectBlockTypes(Object value, Set<String> result) {
        if (!(value instanceof List<?> blocks)) {
            return;
        }
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> map && map.get("type") != null) {
                result.add(String.valueOf(map.get("type")).toLowerCase());
            }
        }
    }

    private AgentArtifactVersion version(String artifactId, int number, AgentArtifactContentModeEnum mode,
                                         Map<String, Object> content, String runId,
                                         Integer supersedesVersion, Date now) {
        AgentArtifactVersion version = new AgentArtifactVersion();
        version.setArtifactId(artifactId);
        version.setVersion(number);
        version.setContentMode(mode == null ? AgentArtifactContentModeEnum.SNAPSHOT : mode);
        version.setContent(new LinkedHashMap<>(content));
        version.setContentHash(sha256(JSON.toJSONString(content)));
        version.setCreatedByRunId(StringUtils.trimToNull(runId));
        version.setCreatedAt(now);
        version.setSupersedesVersion(supersedesVersion);
        return version;
    }

    private List<AgentArtifactEvidence> evidence(List<AgentArtifactEvidence> inputs, String artifactId,
                                                 int artifactVersion, String taskId, Date now) {
        List<AgentArtifactEvidence> result = new ArrayList<>();
        for (AgentArtifactEvidence input : inputs == null ? List.<AgentArtifactEvidence>of() : inputs) {
            if (input == null || StringUtils.isBlank(input.getRunId())) {
                throw new IllegalArgumentException("artifact evidence run id is required");
            }
            requireRunBelongsToTask(input.getRunId(), taskId);
            AgentArtifactEvidence copy = new AgentArtifactEvidence();
            copy.setId(UUID.randomUUID().toString());
            copy.setArtifactId(artifactId);
            copy.setArtifactVersion(artifactVersion);
            copy.setRunId(input.getRunId());
            copy.setToolAttemptId(StringUtils.trimToNull(input.getToolAttemptId()));
            copy.setDataSourceId(input.getDataSourceId());
            copy.setDatabaseName(StringUtils.trimToNull(input.getDatabaseName()));
            copy.setSchemaName(StringUtils.trimToNull(input.getSchemaName()));
            copy.setSqlSnapshot(StringUtils.trimToNull(input.getSqlSnapshot()));
            copy.setSqlHash(StringUtils.isBlank(input.getSqlSnapshot())
                    ? StringUtils.trimToNull(input.getSqlHash()) : sha256(input.getSqlSnapshot()));
            copy.setExecutedAt(input.getExecutedAt());
            copy.setRowCount(input.getRowCount());
            copy.setResultSnapshotId(StringUtils.trimToNull(input.getResultSnapshotId()));
            copy.setCreatedAt(now);
            result.add(copy);
        }
        return result;
    }

    private void validateCreate(AgentArtifactCreateRequest request) {
        if (request == null || StringUtils.isBlank(request.getTaskId())) {
            throw new IllegalArgumentException("artifact request and task id are required");
        }
        if (request.getType() == null) {
            throw new IllegalArgumentException("artifact type is required");
        }
        requireContent(request.getContent());
    }

    private void requireContent(Map<String, Object> content) {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("artifact content is required");
        }
    }

    private AgentTask requireTask(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new IllegalArgumentException("task id is required");
        }
        AgentTask task = storage.getTask(taskId);
        if (task == null) {
            throw new NoSuchElementException("task not found: " + taskId);
        }
        return task;
    }

    private AgentArtifact requireArtifact(String artifactId) {
        if (StringUtils.isBlank(artifactId)) {
            throw new IllegalArgumentException("artifact id is required");
        }
        AgentArtifact artifact = storage.getArtifact(artifactId);
        if (artifact == null) {
            throw new NoSuchElementException("artifact not found: " + artifactId);
        }
        return artifact;
    }

    private void requireRunBelongsToTask(String runId, String taskId) {
        if (StringUtils.isBlank(runId)) {
            return;
        }
        AgentRun run = storage.getRun(runId);
        if (run == null || !taskId.equals(run.getTaskId())) {
            throw new IllegalArgumentException("artifact run is outside the task");
        }
    }

    private AgentArtifact copy(AgentArtifact source) {
        AgentArtifact copy = new AgentArtifact();
        copy.setId(source.getId());
        copy.setTaskId(source.getTaskId());
        copy.setType(source.getType());
        copy.setTitle(source.getTitle());
        copy.setStatus(source.getStatus());
        copy.setCurrentVersion(source.getCurrentVersion());
        copy.setCreatedByRunId(source.getCreatedByRunId());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setGmtCreate(source.getGmtCreate());
        copy.setGmtModified(source.getGmtModified());
        copy.setRevision(source.getRevision());
        return copy;
    }

    private String defaultTitle(AgentArtifactTypeEnum type) {
        return switch (type) {
            case REPORT -> "Analysis Report";
            case METRIC -> "Metric";
            case CHART -> "Chart";
            case DATA_TABLE -> "Data Table";
            case FILE -> "File";
        };
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
