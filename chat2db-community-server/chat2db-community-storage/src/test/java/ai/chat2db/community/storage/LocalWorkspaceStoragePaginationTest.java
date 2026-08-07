package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.operation.Operation;
import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationLogPageQueryRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationPageQueryRequest;
import ai.chat2db.community.storage.converter.StorageConverterImpl;
import ai.chat2db.community.storage.large.ConsoleStorage;
import ai.chat2db.community.storage.large.OperationLogStorage;
import ai.chat2db.community.storage.small.DataSourceStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for core:storage-2 / core:storage-3 pagination findings.
 */
class LocalWorkspaceStoragePaginationTest {

    private LocalWorkspaceStorage workspaceStorage;

    @BeforeAll
    static void useTempHome() {
        TestHome.init();
    }

    @BeforeEach
    void setUp() {
        workspaceStorage = new LocalWorkspaceStorage(new StorageConverterImpl());
        clear(ConsoleStorage.INSTANCE.getDataList().stream().map(Operation::getId).toList(),
                ConsoleStorage.INSTANCE::delete);
        clear(OperationLogStorage.INSTANCE.getDataList().stream().map(OperationLog::getId).toList(),
                OperationLogStorage.INSTANCE::delete);
        clear(DataSourceStorage.INSTANCE.getDataList().stream().map(DataSource::getId).toList(),
                DataSourceStorage.INSTANCE::delete);
    }

    private static void clear(List<Long> ids, java.util.function.Consumer<Long> deleter) {
        new ArrayList<>(ids).forEach(deleter);
    }

    @Test
    void consoleListReportsFullTotalInsteadOfSliceSize() {
        for (int i = 0; i < 5; i++) {
            ConsoleStorage.INSTANCE.save(new Operation());
        }
        OpsOperationPageQueryRequest request = new OpsOperationPageQueryRequest();
        request.setPageNo(1);
        request.setPageSize(2);

        PageResponse<Operation> page1 = workspaceStorage.consoleList(request);
        assertEquals(2, page1.getData().size());
        assertEquals(5L, page1.getTotal());
        assertTrue(page1.getHasNextPage());

        request.setPageNo(3);
        PageResponse<Operation> page3 = workspaceStorage.consoleList(request);
        assertEquals(1, page3.getData().size());
        assertEquals(5L, page3.getTotal());
        assertFalse(page3.getHasNextPage());
    }

    @Test
    void operationLogListReturnsOnlyTheRequestedSlice() {
        for (int i = 0; i < 5; i++) {
            OperationLogStorage.INSTANCE.save(new OperationLog());
        }
        OpsOperationLogPageQueryRequest request = new OpsOperationLogPageQueryRequest();
        request.setPageNo(1);
        request.setPageSize(2);

        PageResponse<OperationLog> page1 = workspaceStorage.operationLogList(request);
        assertEquals(2, page1.getData().size());
        assertEquals(5L, page1.getTotal());
        assertTrue(page1.getHasNextPage());

        request.setPageNo(3);
        PageResponse<OperationLog> page3 = workspaceStorage.operationLogList(request);
        assertEquals(1, page3.getData().size());
        assertFalse(page3.getHasNextPage());
    }

    @Test
    void listDataSourcesReturnsOnlyTheRequestedSlice() {
        for (int i = 0; i < 3; i++) {
            DataSourceStorage.INSTANCE.save(new DataSource());
        }
        DbDataSourcePageQueryRequest request = new DbDataSourcePageQueryRequest();
        request.setPageNo(1);
        request.setPageSize(2);

        PageResponse<?> page1 = workspaceStorage.listDataSources(request);
        assertEquals(2, page1.getData().size());
        assertEquals(3L, page1.getTotal());
        assertTrue(page1.getHasNextPage());

        request.setPageNo(2);
        PageResponse<?> page2 = workspaceStorage.listDataSources(request);
        assertEquals(1, page2.getData().size());
        assertEquals(3L, page2.getTotal());
        assertFalse(page2.getHasNextPage());
    }

    @Test
    void maxPageSizeOnSecondPageReturnsEmptyPageInsteadOfOverflowingEndIndex() {
        DataSourceStorage.INSTANCE.save(new DataSource());
        DbDataSourcePageQueryRequest request = new DbDataSourcePageQueryRequest();
        request.setPageNo(2);
        request.setPageSize(Integer.MAX_VALUE);

        PageResponse<?> page = workspaceStorage.listDataSources(request);

        assertTrue(page.getData().isEmpty());
        assertEquals(1L, page.getTotal());
        assertFalse(page.getHasNextPage());
    }
}
