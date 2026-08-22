package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.operation.Operation;
import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationLogPageQueryRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationPageQueryRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.storage.converter.StorageConverterImpl;
import ai.chat2db.community.storage.large.ConsoleStorage;
import ai.chat2db.community.storage.large.OperationLogStorage;
import ai.chat2db.community.storage.small.DataSourceStorage;
import ai.chat2db.community.tools.security.AesGcmUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void operationLogListFiltersFullSqlCaseInsensitivelyBeforePagination() {
        String firstDdl = "a".repeat(220) + " FrOm OrDeRs";
        String secondDdl = "b".repeat(220) + " FROM ORDERS";
        Long firstMatchId = saveOperationLog(1L, "sales", "public", firstDdl);
        Long secondMatchId = saveOperationLog(1L, "sales", "public", secondDdl);
        saveOperationLog(1L, "sales", "public", "select * from customers");

        OpsOperationLogPageQueryRequest request = new OpsOperationLogPageQueryRequest();
        request.setPageNo(1);
        request.setPageSize(1);
        request.setSearchKey("from orders");

        PageResponse<OperationLog> page1 = workspaceStorage.operationLogList(request);
        assertEquals(1, page1.getData().size());
        assertEquals(secondMatchId, page1.getData().get(0).getId());
        assertEquals(secondDdl, page1.getData().get(0).getDdl());
        assertEquals(2L, page1.getTotal());
        assertTrue(page1.getHasNextPage());

        request.setPageNo(2);
        PageResponse<OperationLog> page2 = workspaceStorage.operationLogList(request);
        assertEquals(1, page2.getData().size());
        assertEquals(firstMatchId, page2.getData().get(0).getId());
        assertEquals(firstDdl, page2.getData().get(0).getDdl());
        assertEquals(2L, page2.getTotal());
        assertFalse(page2.getHasNextPage());
    }

    @Test
    void operationLogListAppliesAllScopeFiltersBeforePagination() {
        Long firstMatchId = saveOperationLog(7L, "warehouse", "analytics", "select 1");
        Long secondMatchId = saveOperationLog(7L, "warehouse", "analytics", "select 2");
        saveOperationLog(8L, "warehouse", "analytics", "wrong datasource");
        saveOperationLog(7L, "reporting", "analytics", "wrong database");
        saveOperationLog(7L, "warehouse", "staging", "wrong schema");

        OpsOperationLogPageQueryRequest request = new OpsOperationLogPageQueryRequest();
        request.setPageNo(1);
        request.setPageSize(1);
        request.setDataSourceId(7L);
        request.setDatabaseName("warehouse");
        request.setSchemaName("analytics");

        PageResponse<OperationLog> page1 = workspaceStorage.operationLogList(request);
        assertEquals(1, page1.getData().size());
        assertEquals(secondMatchId, page1.getData().get(0).getId());
        assertEquals(2L, page1.getTotal());
        assertTrue(page1.getHasNextPage());

        request.setPageNo(2);
        PageResponse<OperationLog> page2 = workspaceStorage.operationLogList(request);
        assertEquals(1, page2.getData().size());
        assertEquals(firstMatchId, page2.getData().get(0).getId());
        assertEquals(2L, page2.getTotal());
        assertFalse(page2.getHasNextPage());
    }

    @Test
    void operationLogListSeparatesSqlExecuteAndSqlAuditStreamsBeforePagination() {
        Long firstExecuteId = saveOperationLog(1L, "sales", "public", "select 1", "SQL_EXECUTE");
        Long secondExecuteId = saveOperationLog(1L, "sales", "public", "select 2", "SQL_EXECUTE");
        saveOperationLog(1L, "sales", "public", "select 3", "SQL_AUDIT");
        saveOperationLog(1L, "sales", "public", "select 4", "SQL_AUDIT");

        OpsOperationLogPageQueryRequest request = new OpsOperationLogPageQueryRequest();
        request.setPageNo(1);
        request.setPageSize(1);
        request.setOperationType("SQL_EXECUTE");

        PageResponse<OperationLog> executePage1 = workspaceStorage.operationLogList(request);
        assertEquals(1, executePage1.getData().size());
        assertEquals(secondExecuteId, executePage1.getData().get(0).getId());
        assertEquals("SQL_EXECUTE", executePage1.getData().get(0).getOperationType());
        assertEquals(2L, executePage1.getTotal());
        assertTrue(executePage1.getHasNextPage());

        request.setPageNo(2);
        PageResponse<OperationLog> executePage2 = workspaceStorage.operationLogList(request);
        assertEquals(1, executePage2.getData().size());
        assertEquals(firstExecuteId, executePage2.getData().get(0).getId());
        assertEquals("SQL_EXECUTE", executePage2.getData().get(0).getOperationType());
        assertEquals(2L, executePage2.getTotal());
        assertFalse(executePage2.getHasNextPage());

        request.setPageNo(1);
        request.setOperationType("SQL_AUDIT");
        PageResponse<OperationLog> auditPage1 = workspaceStorage.operationLogList(request);
        assertEquals(1, auditPage1.getData().size());
        assertEquals("select 4", auditPage1.getData().get(0).getDdl());
        assertEquals(2L, auditPage1.getTotal());
        assertTrue(auditPage1.getHasNextPage());

        request.setPageNo(2);
        PageResponse<OperationLog> auditPage2 = workspaceStorage.operationLogList(request);
        assertEquals(1, auditPage2.getData().size());
        assertEquals("select 3", auditPage2.getData().get(0).getDdl());
        assertEquals(2L, auditPage2.getTotal());
        assertFalse(auditPage2.getHasNextPage());
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

    @Test
    void identityColorIsReturnedByDetailListAndTreeAndCanBeCleared() {
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setAlias("colored datasource");
        dataSource.setIdentityColor("  #12abef  ");
        Long id = workspaceStorage.createDataSource(dataSource);

        assertEquals("#12ABEF", workspaceStorage.queryDataSourceById(id, false).getIdentityColor());

        DbDataSourcePageQueryRequest request = new DbDataSourcePageQueryRequest();
        request.queryAll();
        WorkspaceDataSource listed = workspaceStorage.listDataSources(request).getData().get(0);
        assertEquals("#12ABEF", listed.getIdentityColor());

        Node dataSourceNode = workspaceStorage.getTree().stream()
                .filter(node -> id.equals(node.getId()))
                .findFirst()
                .orElseThrow();
        DataSource treeDataSource = assertInstanceOf(DataSource.class, dataSourceNode.getData());
        assertEquals("#12ABEF", treeDataSource.getIdentityColor());

        workspaceStorage.updateDataSourceIdentityColor(id, " #aa00bb ");
        assertEquals("#AA00BB", workspaceStorage.queryDataSourceById(id, false).getIdentityColor());

        WorkspaceDataSource fullConnectionUpdate = new WorkspaceDataSource();
        fullConnectionUpdate.setId(id);
        fullConnectionUpdate.setAlias("renamed datasource");
        fullConnectionUpdate.setIdentityColor("#12ABEF");
        workspaceStorage.updateDataSource(fullConnectionUpdate);
        assertEquals("#AA00BB", workspaceStorage.queryDataSourceById(id, false).getIdentityColor());

        workspaceStorage.updateDataSourceIdentityColor(id, null);

        assertNull(workspaceStorage.queryDataSourceById(id, false).getIdentityColor());
        assertNull(DataSourceStorage.INSTANCE.getById(id).getIdentityColor());
    }

    @Test
    void fullUpdateStillPreservesStoredPasswordInsideDatasourceStorage() {
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        System.setProperty(AesGcmUtil.KEY_PROPERTY, key);
        try {
            WorkspaceDataSource dataSource = new WorkspaceDataSource();
            dataSource.setAlias("password datasource");
            dataSource.setPassword("secret");
            Long id = workspaceStorage.createDataSource(dataSource);
            String encryptedPassword = DataSourceStorage.INSTANCE.getById(id).getPassword();
            WorkspaceDataSource update = new WorkspaceDataSource();
            update.setId(id);
            update.setAlias("renamed datasource");

            workspaceStorage.updateDataSource(update);

            assertEquals(encryptedPassword, DataSourceStorage.INSTANCE.getById(id).getPassword());
        } finally {
            System.clearProperty(AesGcmUtil.KEY_PROPERTY);
        }
    }

    private static Long saveOperationLog(Long dataSourceId, String databaseName, String schemaName, String ddl) {
        return saveOperationLog(dataSourceId, databaseName, schemaName, ddl, null);
    }

    private static Long saveOperationLog(Long dataSourceId, String databaseName, String schemaName, String ddl,
            String operationType) {
        OperationLog log = new OperationLog();
        log.setDataSourceId(dataSourceId);
        log.setDatabaseName(databaseName);
        log.setSchemaName(schemaName);
        log.setDdl(ddl);
        log.setOperationType(operationType);
        return OperationLogStorage.INSTANCE.save(log);
    }
}
