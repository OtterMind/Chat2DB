package ai.chat2db.community.domain.api.model.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
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
}
