package ai.chat2db.community.domain.api.model.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical manifest hashing shared by planning and durable storage boundaries. */
public final class ImportManifestIntegrity {

    private ImportManifestIntegrity() {
    }

    public static String calculate(ImportManifest manifest) {
        if (manifest == null || manifest.getTaskId() == null || manifest.getMode() == null) {
            throw new IllegalArgumentException("Manifest identity and mode are required");
        }
        StringBuilder canonical = new StringBuilder().append(manifest.getSchemaVersion()).append('|')
                .append(manifest.getTaskId()).append('|').append(value(manifest.getAdmissionVerdict())).append('|')
                .append(value(manifest.getSourceFingerprint())).append('|').append(manifest.getMode().name());
        List<ImportTableDependency> dependencies = manifest.getDependencies() == null
                ? List.of() : manifest.getDependencies();
        dependencies.stream().sorted(Comparator.comparing(ImportTableDependency::getParentTable)
                .thenComparing(ImportTableDependency::getParentColumn,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ImportTableDependency::getChildTable)
                .thenComparing(ImportTableDependency::getChildColumn,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(edge -> canonical.append("|D:").append(edge.getParentTable()).append('.')
                        .append(edge.getParentColumn()).append('>').append(edge.getChildTable()).append('.')
                        .append(edge.getChildColumn()).append(':').append(edge.isLogical()));
        List<ImportManifestShard> shards = manifest.getShards() == null ? List.of() : manifest.getShards();
        shards.forEach(shard -> canonical.append("|S:").append(shard.getShardId()).append(':')
                .append(shard.getTableName()).append(':').append(shard.getLayer()).append(':')
                .append(value(shard.getShardKey())).append(':').append(value(shard.getLowerBound())).append(':')
                .append(value(shard.getUpperBound())).append(':').append(shard.getSourcePath()).append(':')
                .append(shard.getEstimatedRows()).append(':').append(value(shard.getExpectedChecksum())).append(':')
                .append(String.join(",", shard.getDependencyShardIds() == null
                        ? List.of() : shard.getDependencyShardIds())));
        if (manifest.getSchemaVersion() >= 2) {
            appendVersionTwo(canonical, manifest, dependencies, shards);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static void requireValid(ImportManifest manifest) {
        if (manifest == null || manifest.getManifestFingerprint() == null
                || !Objects.equals(manifest.getManifestFingerprint(), calculate(manifest))) {
            throw new IllegalStateException("Import manifest fingerprint does not match its content");
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void appendVersionTwo(StringBuilder canonical, ImportManifest manifest,
            List<ImportTableDependency> dependencies, List<ImportManifestShard> shards) {
        canonical.append("|V2:rows=").append(manifest.getTotalEstimatedRows());
        ImportDependencyPlan plan = manifest.getDependencyPlan();
        if (plan == null || plan.getMode() == null) {
            canonical.append("|P:");
        } else {
            canonical.append("|P:").append(plan.getMode().name())
                    .append(':').append(plan.isStagingRequired())
                    .append(':').append(plan.isCycleResolutionRequired());
            appendNested(canonical, "L", plan.getLayers());
            appendNested(canonical, "C", plan.getCyclicComponents());
            canonical.append("|R:").append(String.join(",", plan.getSelfReferencingTables() == null
                    ? List.of() : plan.getSelfReferencingTables()));
            if (plan.getShardKeys() != null) {
                plan.getShardKeys().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> canonical.append("|K:").append(entry.getKey()).append('=')
                                .append(value(entry.getValue())));
            }
        }
        dependencies.stream().sorted(Comparator.comparing(ImportTableDependency::getParentTableKey,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ImportTableDependency::getChildTableKey,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ImportTableDependency::getKeySequence,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ImportTableDependency::getParentColumn,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ImportTableDependency::getChildColumn,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ImportTableDependency::getConstraintName,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(edge -> canonical.append("|D2:")
                        .append(value(edge.getParentDatabaseName())).append(':')
                        .append(value(edge.getParentSchemaName())).append(':')
                        .append(value(edge.getParentTableKey())).append(':')
                        .append(value(edge.getChildDatabaseName())).append(':')
                        .append(value(edge.getChildSchemaName())).append(':')
                        .append(value(edge.getChildTableKey())).append(':')
                        .append(value(edge.getConstraintName())).append(':')
                        .append(edge.getKeySequence()).append(':').append(edge.getDeferrability()));
        shards.forEach(shard -> canonical.append("|S2:")
                .append(value(shard.getDatabaseName())).append(':')
                .append(value(shard.getSchemaName())).append(':')
                .append(value(shard.getTableKey())));
    }

    private static void appendNested(StringBuilder canonical, String prefix, List<List<String>> values) {
        List<List<String>> groups = values == null ? List.of() : values;
        for (int index = 0; index < groups.size(); index++) {
            canonical.append('|').append(prefix).append(index).append(':')
                    .append(String.join(",", groups.get(index)));
        }
    }
}
