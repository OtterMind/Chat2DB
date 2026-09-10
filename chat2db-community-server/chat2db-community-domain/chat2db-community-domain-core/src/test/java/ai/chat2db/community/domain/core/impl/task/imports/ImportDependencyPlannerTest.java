package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportAdmissionPolicy;
import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportDependencyPlannerTest {

    @Test
    void selectsShardModeWhenEveryTableSharesAnIndependentBusinessKey() {
        ImportDependencyPlan plan = plan(List.of("orders", "order_items"),
                List.of(edge("orders", "order_items")),
                Map.of("orders", "tenant_id", "order_items", "tenant_id"),
                "PARALLEL_SAFE", ImportAdmissionPolicy.STRICT, true);

        assertEquals(ImportPlanMode.PARALLEL_SHARD, plan.getMode());
        assertEquals(List.of(List.of("orders"), List.of("order_items")), plan.getLayers());
        assertFalse(plan.isCycleResolutionRequired());
    }

    @Test
    void compressesCyclesAndKeepsKahnLayersDeterministic() {
        ImportDependencyPlan plan = plan(List.of("account", "profile", "audit"),
                List.of(edge("account", "profile"), edge("profile", "account"), edge("profile", "audit")),
                Map.of(), "PARALLEL_DEGRADED", ImportAdmissionPolicy.STRICT, true);

        assertEquals(ImportPlanMode.PARALLEL_LAYER, plan.getMode());
        assertEquals(List.of(List.of("account", "profile")), plan.getCyclicComponents());
        assertEquals(List.of(List.of("account", "profile"), List.of("audit")), plan.getLayers());
        assertTrue(plan.isCycleResolutionRequired());
    }

    @Test
    void recordsSelfReferencesWithoutCreatingAnSccCycle() {
        ImportDependencyPlan plan = plan(List.of("category"),
                List.of(edge("category", "category")), Map.of("category", "tenant_id"),
                "PARALLEL_DEGRADED", ImportAdmissionPolicy.STRICT, true);

        assertEquals(List.of("category"), plan.getSelfReferencingTables());
        assertTrue(plan.getCyclicComponents().isEmpty());
        assertTrue(plan.isCycleResolutionRequired());
        assertEquals(ImportPlanMode.PARALLEL_LAYER, plan.getMode());
    }

    @Test
    void forcesThirdPartySourcesThroughStaging() {
        ImportDependencyPlan plan = plan(List.of("orders"), List.of(), Map.of("orders", "tenant_id"),
                "PARALLEL_DEGRADED", ImportAdmissionPolicy.STRICT, false);

        assertEquals(ImportPlanMode.STAGING_FIRST, plan.getMode());
        assertTrue(plan.isStagingRequired());
    }

    @Test
    void mapsForbiddenAdmissionToConfiguredPolicy() {
        ImportDependencyPlan strict = plan(List.of("orders"), List.of(), Map.of(),
                "PARALLEL_FORBIDDEN", ImportAdmissionPolicy.STRICT, true);
        ImportDependencyPlan moderate = plan(List.of("orders"), List.of(), Map.of(),
                "PARALLEL_FORBIDDEN", ImportAdmissionPolicy.MODERATE, true);

        assertEquals(ImportPlanMode.REJECTED, strict.getMode());
        assertEquals(ImportPlanMode.SERIAL_SAFE, moderate.getMode());
    }

    @Test
    void excludesExternalValidationParentsFromTheExecutionDag() {
        ImportDependencyPlan plan = plan(List.of("orders"),
                List.of(edge("tenants", "orders")), Map.of(),
                "PARALLEL_SAFE", ImportAdmissionPolicy.STRICT, true);

        assertEquals(List.of(List.of("orders")), plan.getLayers());
        assertTrue(plan.getCyclicComponents().isEmpty());
        assertFalse(plan.isCycleResolutionRequired());
    }

    @Test
    void keepsCaseDistinctQuotedTableDependencyAsAnEdgeRatherThanASelfReference() {
        ImportDependencyPlan plan = plan(List.of("app.public.Users", "app.public.users"),
                List.of(edge("app.public.Users", "app.public.users")), Map.of(),
                "PARALLEL_SAFE", ImportAdmissionPolicy.STRICT, true);

        assertEquals(List.of(List.of("app.public.Users"), List.of("app.public.users")),
                plan.getLayers());
        assertTrue(plan.getSelfReferencingTables().isEmpty());
        assertTrue(plan.getCyclicComponents().isEmpty());
    }

    private ImportDependencyPlan plan(List<String> tables, List<ImportTableDependency> dependencies,
            Map<String, String> shardKeys, String verdict, ImportAdmissionPolicy policy, boolean trusted) {
        return ImportDependencyPlanner.plan(tables, dependencies, shardKeys, verdict, policy, trusted);
    }

    private ImportTableDependency edge(String parent, String child) {
        return ImportTableDependency.builder().parentTable(parent).parentColumn("id")
                .childTable(child).childColumn(parent + "_id").build();
    }
}
