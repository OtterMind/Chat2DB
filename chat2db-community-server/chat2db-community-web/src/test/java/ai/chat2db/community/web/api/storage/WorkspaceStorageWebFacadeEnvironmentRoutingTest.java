package ai.chat2db.community.web.api.storage;

import ai.chat2db.community.domain.api.config.Environment;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSourceNamespace;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.domain.api.service.db.IDbNamespaceService;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.converter.data.source.DataSourceWebConverter;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceQueryRequest;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceNamespaceResponse;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceStorageWebFacadeEnvironmentRoutingTest {

    @Test
    void datasourceReadsUseEnvironmentEnrichedDomainServices() {
        Environment environment = Environment.builder()
                .id(2L)
                .name("RELEASE")
                .shortName("PROD")
                .color("RED")
                .build();
        WorkspaceDataSource workspaceDataSource = new WorkspaceDataSource();
        workspaceDataSource.setId(91L);
        workspaceDataSource.setEnvironmentId(2L);
        workspaceDataSource.setEnvironment(environment);
        WorkspaceDataSourceNamespace namespace = new WorkspaceDataSourceNamespace();
        namespace.setDataSources(List.of(workspaceDataSource));
        namespace.setNamespaces(List.of());
        DataSource treeDataSource = new DataSource();
        treeDataSource.setId(91L);
        treeDataSource.setEnvironmentId(2L);
        treeDataSource.setEnvironment(environment);
        Node treeNode = Node.builder().id(91L).type("DATA_SOURCE").data(treeDataSource).build();
        List<String> calls = new ArrayList<>();

        IWorkspaceStorageFacade storageFacade = proxy(IWorkspaceStorageFacade.class, (method, args) -> {
            if (List.of("listDataSources", "getNamespaceDataSources", "getTree").contains(method)) {
                throw new AssertionError("JCEF read bypassed domain service: " + method);
            }
            return null;
        });
        IDbWorkspaceDataSourceService dataSourceService = proxy(IDbWorkspaceDataSourceService.class,
                (method, args) -> {
                    if ("listDataSources".equals(method)) {
                        calls.add("domain.datasource.list");
                        DbDataSourcePageQueryRequest request = (DbDataSourcePageQueryRequest) args[0];
                        return PageResponse.of(List.of(workspaceDataSource), 1L,
                                request.getPageNo(), request.getPageSize());
                    }
                    return null;
                });
        IDbNamespaceService namespaceService = proxy(IDbNamespaceService.class, (method, args) -> {
            if ("getNamespaceDataSources".equals(method)) {
                calls.add("domain.namespace.list");
                return namespace;
            }
            if ("getTree".equals(method)) {
                calls.add("domain.namespace.tree");
                return List.of(treeNode);
            }
            return null;
        });
        new WorkspaceStorageWebFacade(storageFacade, dataSourceService, namespaceService,
                DataSourceWebConverter.INSTANCE, null);
        DataSourceQueryRequest request = new DataSourceQueryRequest();
        request.setPageNo(1);
        request.setPageSize(10);

        WebPageResult<DataSourceResponse> listResult = WorkspaceStorageWebFacade.getDataSourceList(request);
        DataResult<DataSourceNamespaceResponse> namespaceResult =
                WorkspaceStorageWebFacade.getNamespaceDatasource();
        ListResult<Node> treeResult = WorkspaceStorageWebFacade.getTree();

        assertEquals(List.of("domain.datasource.list", "domain.namespace.list", "domain.namespace.tree"), calls);
        assertEquals("PROD", listResult.getData().getData().get(0).getEnvironment().getShortName());
        assertEquals("PROD", namespaceResult.getData().getDataSources().get(0)
                .getEnvironment().getShortName());
        assertEquals("PROD", ((DataSource) treeResult.getData().get(0).getData())
                .getEnvironment().getShortName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, ProxyHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(method.getName(), args));
    }

    @FunctionalInterface
    private interface ProxyHandler {
        Object invoke(String method, Object[] args);
    }
}
