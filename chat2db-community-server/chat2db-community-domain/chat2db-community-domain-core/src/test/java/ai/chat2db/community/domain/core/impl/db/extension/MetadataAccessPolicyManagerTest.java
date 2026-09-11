package ai.chat2db.community.domain.core.impl.db.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import ai.chat2db.community.domain.api.model.metadata.extension.MetadataAccessContext;
import org.junit.jupiter.api.Test;

class MetadataAccessPolicyManagerTest {

    @Test
    void emptyPolicySetKeepsCommunityBehavior() {
        MetadataAccessPolicyManager manager = new MetadataAccessPolicyManager(List.of());

        assertEquals(List.of("orders", "customers"), manager.filter(List.of("orders", "customers"),
                this::table));
    }

    @Test
    void policiesAreIntersectedWithoutChangingResourceOrder() {
        MetadataAccessPolicyManager manager = new MetadataAccessPolicyManager(List.of(
                resources -> resources.stream().map(resource -> !"secret".equals(resource.getTableName())).toList(),
                resources -> resources.stream().map(resource -> !"audit".equals(resource.getTableName())).toList()
        ));

        assertEquals(List.of("orders"), manager.filter(List.of("secret", "orders", "audit"), this::table));
    }

    @Test
    void malformedPolicyResponseFailsClosed() {
        MetadataAccessPolicyManager manager = new MetadataAccessPolicyManager(List.of(resources -> List.of(true)));

        assertThrows(IllegalStateException.class,
                () -> manager.authorize(List.of(table("orders"), table("customers"))));
    }

    private MetadataAccessContext table(String tableName) {
        return MetadataAccessContext.builder().dataSourceId(7L).databaseName("app")
                .schemaName("public").tableName(tableName).operationType("SELECT").build();
    }
}
