package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.storage.converter.StorageConverterImpl;
import ai.chat2db.community.storage.small.DataSourceStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkspaceStorageDataSourceFilterTest {

    private LocalWorkspaceStorage workspaceStorage;

    @BeforeAll
    static void useTempHome() {
        TestHome.init();
    }

    @BeforeEach
    void setUp() {
        workspaceStorage = new LocalWorkspaceStorage(new StorageConverterImpl());
        List<Long> ids = DataSourceStorage.INSTANCE.getDataList().stream()
                .map(DataSource::getId)
                .toList();
        new ArrayList<>(ids).forEach(DataSourceStorage.INSTANCE::delete);
    }

    @Test
    void listDataSourcesAppliesCaseInsensitiveSearchBeforePagingAndTotal() {
        saveDataSource(null, "PRIVATE");
        saveDataSource("Unrelated", "SHARED");
        saveDataSource("Alpha Primary", "PRIVATE");
        saveDataSource("alpha Replica", "SHARED");
        DbDataSourcePageQueryRequest request = new DbDataSourcePageQueryRequest();
        request.setSearchKey("ALPHA");
        request.setPageNo(1);
        request.setPageSize(1);

        PageResponse<WorkspaceDataSource> page1 = workspaceStorage.listDataSources(request);
        assertEquals(List.of("Alpha Primary"), page1.getData().stream().map(WorkspaceDataSource::getAlias).toList());
        assertEquals(2L, page1.getTotal());
        assertTrue(page1.getHasNextPage());

        request.setPageNo(2);
        PageResponse<WorkspaceDataSource> page2 = workspaceStorage.listDataSources(request);
        assertEquals(List.of("alpha Replica"), page2.getData().stream().map(WorkspaceDataSource::getAlias).toList());
        assertEquals(2L, page2.getTotal());
        assertFalse(page2.getHasNextPage());
    }

    @Test
    void listDataSourcesIgnoresBlankSearchAndKindFilters() {
        saveDataSource(null, "PRIVATE");
        saveDataSource("Beta", "SHARED");
        DbDataSourcePageQueryRequest request = new DbDataSourcePageQueryRequest();
        request.setSearchKey(" \t ");
        request.setKind("   ");
        request.setPageNo(1);
        request.setPageSize(10);

        PageResponse<WorkspaceDataSource> page = workspaceStorage.listDataSources(request);

        assertEquals(2, page.getData().size());
        assertNull(page.getData().get(0).getAlias());
        assertEquals("Beta", page.getData().get(1).getAlias());
        assertEquals(2L, page.getTotal());
    }

    @Test
    void listDataSourcesMatchesKindIgnoringCaseAndTreatsLegacyNullAsPrivate() {
        saveDataSource("Shared First", "SHARED");
        saveDataSource("Legacy Private", null);
        saveDataSource("Private First", "PRIVATE");
        DbDataSourcePageQueryRequest request = new DbDataSourcePageQueryRequest();
        request.setKind("private");
        request.setPageNo(1);
        request.setPageSize(1);

        PageResponse<WorkspaceDataSource> page1 = workspaceStorage.listDataSources(request);
        assertEquals(List.of("Legacy Private"),
                page1.getData().stream().map(WorkspaceDataSource::getAlias).toList());
        assertEquals(2L, page1.getTotal());
        assertTrue(page1.getHasNextPage());

        request.setPageNo(2);
        PageResponse<WorkspaceDataSource> page2 = workspaceStorage.listDataSources(request);
        assertEquals(List.of("Private First"),
                page2.getData().stream().map(WorkspaceDataSource::getAlias).toList());
        assertEquals(2L, page2.getTotal());
        assertFalse(page2.getHasNextPage());
    }

    private static void saveDataSource(String alias, String kind) {
        DataSource dataSource = new DataSource();
        dataSource.setAlias(alias);
        dataSource.setKind(kind);
        DataSourceStorage.INSTANCE.save(dataSource);
    }
}
