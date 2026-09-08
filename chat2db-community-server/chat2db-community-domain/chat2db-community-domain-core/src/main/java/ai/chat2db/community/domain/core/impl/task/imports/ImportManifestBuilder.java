package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates and freezes the shard contract that preprocessing and execution must share. */
public final class ImportManifestBuilder {

    public static final int SCHEMA_VERSION = 1;

    private ImportManifestBuilder() {
    }

    public static ImportManifest build(Long taskId, String admissionVerdict, String sourceFingerprint,
            ImportDependencyPlan plan, List<ImportTableDependency> dependencies,
            List<ImportManifestShard> requestedShards) {
        if (taskId == null || taskId <= 0L) {
            throw new IllegalArgumentException("Manifest task id must be positive");
        }
        if (plan == null || plan.getMode() == null || plan.getMode() == ImportPlanMode.REJECTED) {
            throw new IllegalArgumentException("A rejected or incomplete plan cannot produce a manifest");
        }
        if (StringUtils.isBlank(sourceFingerprint)) {
            throw new IllegalArgumentException("Manifest source fingerprint is required");
        }
        if (requestedShards == null || requestedShards.isEmpty()) {
            throw new IllegalArgumentException("Manifest must contain at least one shard");
        }

        Map<String, Integer> plannedLayers = plannedLayers(plan);
        List<ImportManifestShard> shards = requestedShards.stream()
                .map(ImportManifestBuilder::copy)
                .sorted(Comparator.comparingInt(ImportManifestShard::getLayer)
                        .thenComparing(ImportManifestShard::getTableName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ImportManifestShard::getShardId))
                .toList();
        validateShards(shards, plannedLayers);
        long totalRows = 0L;
        for (ImportManifestShard shard : shards) {
            totalRows = Math.addExact(totalRows, shard.getEstimatedRows());
        }
        List<ImportTableDependency> frozenDependencies = dependencies == null ? List.of()
                : dependencies.stream().map(ImportManifestBuilder::copy).toList();
        String fingerprint = fingerprint(taskId, admissionVerdict, sourceFingerprint, plan.getMode(),
                frozenDependencies, shards);
        return ImportManifest.builder()
                .schemaVersion(SCHEMA_VERSION)
                .taskId(taskId)
                .mode(plan.getMode())
                .admissionVerdict(admissionVerdict)
                .sourceFingerprint(sourceFingerprint)
                .totalEstimatedRows(totalRows)
                .dependencies(List.copyOf(frozenDependencies))
                .shards(List.copyOf(shards))
                .manifestFingerprint(fingerprint)
                .build();
    }

    private static Map<String, Integer> plannedLayers(ImportDependencyPlan plan) {
        Map<String, Integer> result = new HashMap<>();
        List<List<String>> layers = plan.getLayers() == null ? List.of() : plan.getLayers();
        for (int layer = 0; layer < layers.size(); layer++) {
            for (String table : layers.get(layer)) {
                if (StringUtils.isBlank(table)
                        || result.putIfAbsent(table.toLowerCase(Locale.ROOT), layer) != null) {
                    throw new IllegalArgumentException("Dependency plan contains a duplicate or blank table");
                }
            }
        }
        return result;
    }

    private static void validateShards(List<ImportManifestShard> shards, Map<String, Integer> plannedLayers) {
        Set<String> ids = new HashSet<>();
        Map<String, ImportManifestShard> byId = new HashMap<>();
        for (ImportManifestShard shard : shards) {
            if (StringUtils.isAnyBlank(shard.getShardId(), shard.getTableName(), shard.getSourcePath())) {
                throw new IllegalArgumentException("Every manifest shard requires id, table and source path");
            }
            if (!ids.add(shard.getShardId())) {
                throw new IllegalArgumentException("Duplicate manifest shard id: " + shard.getShardId());
            }
            Integer plannedLayer = plannedLayers.get(shard.getTableName().toLowerCase(Locale.ROOT));
            if (plannedLayer == null || plannedLayer != shard.getLayer()) {
                throw new IllegalArgumentException("Shard layer does not match dependency plan: " + shard.getShardId());
            }
            if (shard.getEstimatedRows() < 0L) {
                throw new IllegalArgumentException("Shard estimated rows cannot be negative: " + shard.getShardId());
            }
            boolean hasLower = StringUtils.isNotBlank(shard.getLowerBound());
            boolean hasUpper = StringUtils.isNotBlank(shard.getUpperBound());
            if (hasLower != hasUpper || (hasLower && StringUtils.isBlank(shard.getShardKey()))) {
                throw new IllegalArgumentException("Shard bounds require a key and must be specified together: "
                        + shard.getShardId());
            }
            byId.put(shard.getShardId(), shard);
            if (shard.getDependencyShardIds().stream().anyMatch(StringUtils::isBlank)) {
                throw new IllegalArgumentException("Shard dependencies cannot be blank: " + shard.getShardId());
            }
            if (new HashSet<>(shard.getDependencyShardIds()).size() != shard.getDependencyShardIds().size()) {
                throw new IllegalArgumentException("Shard dependencies cannot contain duplicates: "
                        + shard.getShardId());
            }
        }
        for (ImportManifestShard shard : shards) {
            for (String dependencyId : shard.getDependencyShardIds()) {
                ImportManifestShard dependency = byId.get(dependencyId);
                if (dependency == null) {
                    throw new IllegalArgumentException("Unknown dependency shard: " + dependencyId);
                }
                if (dependency.getLayer() >= shard.getLayer()) {
                    throw new IllegalArgumentException("Shard dependency must commit in an earlier layer: "
                            + shard.getShardId() + " -> " + dependencyId);
                }
            }
        }
    }

    private static ImportManifestShard copy(ImportManifestShard source) {
        if (source == null) {
            throw new IllegalArgumentException("Manifest shard cannot be null");
        }
        return ImportManifestShard.builder()
                .shardId(StringUtils.trimToEmpty(source.getShardId()))
                .tableName(StringUtils.trimToEmpty(source.getTableName()))
                .layer(source.getLayer())
                .shardKey(StringUtils.trimToNull(source.getShardKey()))
                .lowerBound(StringUtils.trimToNull(source.getLowerBound()))
                .upperBound(StringUtils.trimToNull(source.getUpperBound()))
                .sourcePath(StringUtils.trimToEmpty(source.getSourcePath()))
                .estimatedRows(source.getEstimatedRows())
                .expectedChecksum(StringUtils.trimToNull(source.getExpectedChecksum()))
                .dependencyShardIds(source.getDependencyShardIds() == null ? List.of()
                        : source.getDependencyShardIds().stream().sorted().toList())
                .build();
    }

    private static ImportTableDependency copy(ImportTableDependency source) {
        if (source == null || StringUtils.isAnyBlank(source.getParentTable(), source.getChildTable())) {
            throw new IllegalArgumentException("Manifest dependency requires parent and child tables");
        }
        return ImportTableDependency.builder()
                .parentTable(source.getParentTable()).parentColumn(source.getParentColumn())
                .childTable(source.getChildTable()).childColumn(source.getChildColumn())
                .logical(source.isLogical()).build();
    }

    private static String fingerprint(Long taskId, String verdict, String sourceFingerprint, ImportPlanMode mode,
            List<ImportTableDependency> dependencies, List<ImportManifestShard> shards) {
        StringBuilder canonical = new StringBuilder().append(SCHEMA_VERSION).append('|').append(taskId)
                .append('|').append(StringUtils.defaultString(verdict)).append('|').append(sourceFingerprint)
                .append('|').append(mode.name());
        dependencies.stream().sorted(Comparator.comparing(ImportTableDependency::getParentTable)
                .thenComparing(ImportTableDependency::getParentColumn,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ImportTableDependency::getChildTable)
                .thenComparing(ImportTableDependency::getChildColumn,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(edge -> canonical.append("|D:").append(edge.getParentTable()).append('.')
                        .append(edge.getParentColumn()).append('>').append(edge.getChildTable()).append('.')
                        .append(edge.getChildColumn()).append(':').append(edge.isLogical()));
        shards.forEach(shard -> canonical.append("|S:").append(shard.getShardId()).append(':')
                .append(shard.getTableName()).append(':').append(shard.getLayer()).append(':')
                .append(StringUtils.defaultString(shard.getShardKey())).append(':')
                .append(StringUtils.defaultString(shard.getLowerBound())).append(':')
                .append(StringUtils.defaultString(shard.getUpperBound())).append(':')
                .append(shard.getSourcePath()).append(':').append(shard.getEstimatedRows()).append(':')
                .append(StringUtils.defaultString(shard.getExpectedChecksum())).append(':')
                .append(String.join(",", shard.getDependencyShardIds())));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
