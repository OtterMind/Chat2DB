package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestIntegrity;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Validates and freezes the shard contract that preprocessing and execution must share. */
public final class ImportManifestBuilder {

    public static final int SCHEMA_VERSION = 2;

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
        ImportManifest manifest = ImportManifest.builder()
                .schemaVersion(SCHEMA_VERSION)
                .taskId(taskId)
                .mode(plan.getMode())
                .admissionVerdict(admissionVerdict)
                .sourceFingerprint(sourceFingerprint)
                .totalEstimatedRows(totalRows)
                .dependencyPlan(copy(plan))
                .dependencies(List.copyOf(frozenDependencies))
                .shards(List.copyOf(shards))
                .build();
        manifest.setManifestFingerprint(ImportManifestIntegrity.calculate(manifest));
        return manifest;
    }

    private static Map<String, Integer> plannedLayers(ImportDependencyPlan plan) {
        Map<String, Integer> result = new HashMap<>();
        List<List<String>> layers = plan.getLayers() == null ? List.of() : plan.getLayers();
        for (int layer = 0; layer < layers.size(); layer++) {
            for (String table : layers.get(layer)) {
                if (StringUtils.isBlank(table)
                        || result.putIfAbsent(table, layer) != null) {
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
            requireExactShardIdentity(shard);
            if (StringUtils.isAnyBlank(shard.getShardId(), shard.getTableName(), shard.getSourcePath(),
                    shard.getExpectedChecksum())) {
                throw new IllegalArgumentException("Every manifest shard requires id, table, source path and checksum");
            }
            if (!ids.add(shard.getShardId())) {
                throw new IllegalArgumentException("Duplicate manifest shard id: " + shard.getShardId());
            }
            Integer plannedLayer = plannedLayers.get(tableNode(shard));
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

    static void requireVersionedShardIdentities(ImportManifest manifest) {
        if (manifest == null || manifest.getSchemaVersion() == 1) {
            return;
        }
        if (manifest.getSchemaVersion() < 1) {
            throw new IllegalArgumentException("Unsupported import manifest schema version: "
                    + manifest.getSchemaVersion());
        }
        List<ImportManifestShard> shards = manifest.getShards() == null
                ? List.of() : manifest.getShards();
        shards.forEach(ImportManifestBuilder::requireExactShardIdentity);
    }

    static void requireVersionedShardIdentity(int schemaVersion, ImportManifestShard shard) {
        if (schemaVersion == 1) {
            return;
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("Unsupported import manifest schema version: "
                    + schemaVersion);
        }
        requireExactShardIdentity(shard);
    }

    private static void requireExactShardIdentity(ImportManifestShard shard) {
        if (shard == null || StringUtils.isBlank(shard.getTableKey())) {
            throw new IllegalArgumentException("Schema v2 manifest shard requires an exact tableKey");
        }
        String expected = ImportTaskSourceSupport.tableKey(shard.getDatabaseName(),
                shard.getSchemaName(), shard.getTableName());
        if (!shard.getTableKey().equals(expected)) {
            throw new IllegalArgumentException("Schema v2 manifest shard tableKey does not match its exact target: "
                    + shard.getTableKey() + " != " + expected);
        }
    }

    private static ImportManifestShard copy(ImportManifestShard source) {
        if (source == null) {
            throw new IllegalArgumentException("Manifest shard cannot be null");
        }
        return ImportManifestShard.builder()
                .shardId(StringUtils.trimToEmpty(source.getShardId()))
                .databaseName(StringUtils.trimToNull(source.getDatabaseName()))
                .schemaName(StringUtils.trimToNull(source.getSchemaName()))
                .tableName(StringUtils.trimToEmpty(source.getTableName()))
                .tableKey(StringUtils.trimToNull(source.getTableKey()))
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
                .parentDatabaseName(source.getParentDatabaseName())
                .parentSchemaName(source.getParentSchemaName())
                .parentTable(source.getParentTable()).parentColumn(source.getParentColumn())
                .parentTableKey(source.getParentTableKey())
                .childDatabaseName(source.getChildDatabaseName())
                .childSchemaName(source.getChildSchemaName())
                .childTable(source.getChildTable()).childColumn(source.getChildColumn())
                .childTableKey(source.getChildTableKey())
                .constraintName(source.getConstraintName())
                .keySequence(source.getKeySequence())
                .deferrability(source.getDeferrability())
                .logical(source.isLogical()).build();
    }

    private static ImportDependencyPlan copy(ImportDependencyPlan source) {
        return ImportDependencyPlan.builder()
                .mode(source.getMode())
                .layers(copyNested(source.getLayers()))
                .cyclicComponents(copyNested(source.getCyclicComponents()))
                .selfReferencingTables(source.getSelfReferencingTables() == null ? List.of()
                        : List.copyOf(source.getSelfReferencingTables()))
                .shardKeys(source.getShardKeys() == null ? Map.of()
                        : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source.getShardKeys())))
                .stagingRequired(source.isStagingRequired())
                .cycleResolutionRequired(source.isCycleResolutionRequired())
                .build();
    }

    private static List<List<String>> copyNested(List<List<String>> source) {
        return source == null ? List.of() : source.stream().map(List::copyOf).toList();
    }

    private static String tableNode(ImportManifestShard shard) {
        return StringUtils.defaultIfBlank(shard.getTableKey(), shard.getTableName());
    }

}
