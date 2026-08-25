package ai.chat2db.community.storage.datawiki;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiColumn;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class H2DataWikiStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsStructuredWikiAndProtectsRevision() {
        H2DataWikiStorage storage = new H2DataWikiStorage(dataSource(tempDir.resolve("datawiki")));
        DataWikiDefinition wiki = wiki();

        DataWikiDefinition created = storage.create(wiki);
        assertEquals("orders", created.getResources().get(0).getTableName());
        assertEquals("Order number", created.getResources().get(0).getColumns().get(0).getBusinessDescription());

        created.setName("Sales Wiki v2");
        created.setRevision(2L);
        created.setGmtModified(new Date(2_000L));
        assertEquals("Sales Wiki v2", storage.update(created, 1L).getName());
        assertThrows(RuntimeException.class, () -> storage.update(created, 1L));

        storage.delete(created.getId(), 2L);
        assertNull(storage.get(created.getId()));
    }

    private static javax.sql.DataSource dataSource(Path path) {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:file:" + path.toAbsolutePath() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static DataWikiDefinition wiki() {
        DataWikiColumn column = new DataWikiColumn();
        column.setName("order_no");
        column.setDataType("varchar(64)");
        column.setBusinessDescription("Order number");
        DataWikiResource resource = new DataWikiResource();
        resource.setId("resource-1");
        resource.setDataSourceId(1L);
        resource.setTableName("orders");
        resource.setColumns(List.of(column));
        DataWikiDefinition wiki = new DataWikiDefinition();
        wiki.setId("wiki-1");
        wiki.setName("Sales Wiki");
        wiki.setResources(List.of(resource));
        wiki.setCreatedBy(7L);
        wiki.setGmtCreate(new Date(1_000L));
        wiki.setGmtModified(new Date(1_000L));
        wiki.setRevision(1L);
        return wiki;
    }
}
