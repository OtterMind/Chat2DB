package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportManifestBuilderTest {

    @Test
    void freezesSortedShardsAndBuildsAStableFingerprint() {
        ImportDependencyPlan plan = plan();
        List<ImportManifestShard> shards = List.of(
                shard("items-1", "order_items", 1, 20, List.of("orders-1")),
                shard("orders-1", "orders", 0, 10, List.of()));

        ImportManifest first = ImportManifestBuilder.build(42L, "PARALLEL_SAFE", "source-sha",
                plan, List.of(edge()), shards);
        ImportManifest second = ImportManifestBuilder.build(42L, "PARALLEL_SAFE", "source-sha",
                plan, List.of(edge()), List.of(shards.get(1), shards.get(0)));

        assertEquals(30L, first.getTotalEstimatedRows());
        assertEquals(List.of("orders-1", "items-1"),
                first.getShards().stream().map(ImportManifestShard::getShardId).toList());
        assertEquals(first.getManifestFingerprint(), second.getManifestFingerprint());
        assertEquals(plan.getLayers(), first.getDependencyPlan().getLayers());
        assertNotSame(plan, first.getDependencyPlan());
        assertEquals(2, first.getSchemaVersion());
    }

    @Test
    void rejectsDependenciesThatDoNotCrossACommittedLayerBarrier() {
        List<ImportManifestShard> shards = List.of(
                shard("orders-1", "orders", 0, 10, List.of("items-1")),
                shard("items-1", "order_items", 1, 20, List.of()));

        assertThrows(IllegalArgumentException.class, () -> ImportManifestBuilder.build(
                42L, "PARALLEL_SAFE", "source-sha", plan(), List.of(edge()), shards));
    }

    @Test
    void rejectsHalfOpenRangeMetadataWithoutBothBounds() {
        ImportManifestShard invalid = shard("orders-1", "orders", 0, 10, List.of());
        invalid.setUpperBound(null);

        assertThrows(IllegalArgumentException.class, () -> ImportManifestBuilder.build(
                42L, "PARALLEL_SAFE", "source-sha", plan(), List.of(edge()), List.of(invalid)));
    }

    @Test
    void rejectsShardWithoutChecksum() {
        ImportManifestShard invalid = shard("orders-1", "orders", 0, 10, List.of());
        invalid.setExpectedChecksum(null);

        assertThrows(IllegalArgumentException.class, () -> ImportManifestBuilder.build(
                42L, "PARALLEL_SAFE", "source-sha", plan(), List.of(edge()), List.of(invalid)));
    }

    @Test
    void rejectsSchemaV2ShardWithoutAnExactTableKey() {
        ImportManifestShard invalid = shard("orders-1", "orders", 0, 10, List.of());
        invalid.setTableKey(null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ImportManifestBuilder.build(42L, "PARALLEL_SAFE", "source-sha",
                        plan(), List.of(edge()), List.of(invalid)));

        assertEquals("Schema v2 manifest shard requires an exact tableKey", failure.getMessage());
    }

    @Test
    void rejectsSchemaV2ShardWhoseTableKeyDiffersFromItsExactTuple() {
        ImportManifestShard invalid = shard("orders-1", "orders", 0, 10, List.of());
        invalid.setTableKey("Orders");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ImportManifestBuilder.build(42L, "PARALLEL_SAFE", "source-sha",
                        plan(), List.of(edge()), List.of(invalid)));

        assertEquals("Schema v2 manifest shard tableKey does not match its exact target: Orders != orders",
                failure.getMessage());
    }

    @Test
    void acceptsCaseDistinctQuotedTablesAsDifferentPlanNodes() {
        ImportDependencyPlan caseDistinctPlan = ImportDependencyPlan.builder()
                .mode(ImportPlanMode.PARALLEL_LAYER)
                .layers(List.of(List.of("Users"), List.of("users")))
                .build();
        List<ImportManifestShard> shards = List.of(
                shard("Users-exact-1", "Users", 0, 1, List.of()),
                shard("users-exact-1", "users", 1, 1, List.of("Users-exact-1")));

        ImportManifest manifest = ImportManifestBuilder.build(43L, "PARALLEL_SAFE",
                "source-case", caseDistinctPlan, List.of(), shards);

        assertEquals(List.of("Users", "users"), manifest.getShards().stream()
                .map(ImportManifestShard::getTableName).toList());
    }

    private ImportDependencyPlan plan() {
        return ImportDependencyPlan.builder().mode(ImportPlanMode.PARALLEL_LAYER)
                .layers(List.of(List.of("orders"), List.of("order_items"))).build();
    }

    private ImportTableDependency edge() {
        return ImportTableDependency.builder().parentTable("orders").parentColumn("id")
                .childTable("order_items").childColumn("order_id").build();
    }

    private ImportManifestShard shard(String id, String table, int layer, long rows, List<String> dependencies) {
        return ImportManifestShard.builder().shardId(id).tableName(table).layer(layer)
                .tableKey(table)
                .shardKey("id").lowerBound("1").upperBound("100")
                .sourcePath("staged/" + id + ".csv").estimatedRows(rows)
                .expectedChecksum("crc-" + id).dependencyShardIds(dependencies).build();
    }
}
