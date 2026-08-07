package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.Environment;
import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.workspace.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class DataSourceEnvironmentEnricherTest {

    @Test
    void treeHydratesEnvironmentOnCopiedDataSource() {
        DataSource source = new DataSource();
        source.setId(9L);
        source.setEnvironmentId(2L);
        Node node = Node.builder().id(9L).type("DATA_SOURCE").data(source).build();
        DataSourceEnvironmentEnricher enricher = new DataSourceEnvironmentEnricher(() -> List.of(
                Environment.builder().id(2L).name("RELEASE").shortName("Release Environment").color("RED").build()));

        Node resultNode = enricher.enrichTree(List.of(node)).get(0);
        DataSource result = (DataSource) resultNode.getData();

        assertNotSame(node, resultNode);
        assertNotSame(source, result);
        assertEquals("RELEASE", result.getEnvironment().getName());
        assertEquals("RED", result.getEnvironment().getColor());
        assertNull(source.getEnvironment());
    }
}
