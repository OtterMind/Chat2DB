package ai.chat2db.community.domain.core.impl.datawiki;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiColumn;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocument;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocumentBundle;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiResource;
import ai.chat2db.community.domain.api.service.storage.IDataWikiDocumentStorage;
import ai.chat2db.community.domain.api.service.storage.IDataWikiStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataWikiServiceImplTest {

    @Test
    void rendersResourceWithoutOptionalNamespaceAsMarkdown() {
        DataWikiColumn column = new DataWikiColumn();
        column.setName("order_no");
        column.setDataType("varchar(64)");
        column.setBusinessDescription("Customer-facing order number");
        DataWikiResource resource = new DataWikiResource();
        resource.setId("resource-1");
        resource.setDataSourceId(1L);
        resource.setDataSourceName("Sales");
        resource.setTableName("orders");
        resource.setColumns(List.of(column));
        DataWikiDefinition wiki = new DataWikiDefinition();
        wiki.setId("wiki-1");
        wiki.setName("Sales Wiki");
        wiki.setRevision(3L);
        wiki.setResources(List.of(resource));

        DataWikiServiceImpl service = new DataWikiServiceImpl(new ReadOnlyStorage(wiki), new MemoryDocumentStorage());
        DataWikiDocumentBundle bundle = service.documents(wiki.getId());
        String markdown = bundle.getDocuments().get(0).getContent();

        assertTrue(markdown.contains("# Sales Wiki"));
        assertTrue(markdown.contains("Sales.orders"));
        assertTrue(markdown.contains("How agents should use this wiki"));
        assertEquals(2, bundle.getDocuments().size());
        DataWikiDocument tableDocument = bundle.getDocuments().get(1);
        assertEquals("tables/Sales/_default/orders.md", tableDocument.getPath());
        assertTrue(tableDocument.getContent().contains("# Sales.orders"));
        assertTrue(tableDocument.getContent().contains("Customer-facing order number"));
    }

    private record ReadOnlyStorage(DataWikiDefinition wiki) implements IDataWikiStorage {
        @Override public DataWikiDefinition create(DataWikiDefinition dataWiki) { throw new UnsupportedOperationException(); }
        @Override public DataWikiDefinition get(String id) { return wiki; }
        @Override public List<DataWikiDefinition> list() { return List.of(wiki); }
        @Override public DataWikiDefinition update(DataWikiDefinition dataWiki, long expectedRevision) {
            throw new UnsupportedOperationException();
        }
        @Override public void delete(String id, long expectedRevision) { throw new UnsupportedOperationException(); }
    }

    private static class MemoryDocumentStorage implements IDataWikiDocumentStorage {
        private DataWikiDocumentBundle bundle;

        @Override public String synchronize(String dataWikiId, long revision, List<DataWikiDocument> documents) {
            bundle = new DataWikiDocumentBundle();
            bundle.setDataWikiId(dataWikiId);
            bundle.setRevision(revision);
            bundle.setRootDirectory("/datawiki/" + dataWikiId);
            bundle.setDocuments(documents);
            return "/datawiki/" + dataWikiId;
        }
        @Override public DataWikiDocumentBundle load(String dataWikiId, long expectedRevision) { return bundle; }
        @Override public String read(String dataWikiId, String documentPath) {
            return bundle.getDocuments().stream().filter(item -> item.getPath().equals(documentPath))
                    .findFirst().orElseThrow().getContent();
        }
        @Override public void delete(String dataWikiId) { }
    }
}
