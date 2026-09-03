package ai.chat2db.community.domain.core.impl.ncx;

import ai.chat2db.community.domain.api.model.ncx.NcxImportResponse;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskNcxImportServiceDatagripMalformedXmlTest {

    @Test
    void datagripUploadFileSkipsConnectionXmlMissingRequiredNodes() {
        AtomicReference<WorkspaceDataSource> captured = new AtomicReference<>();
        TaskNcxImportServiceImpl service = new TaskNcxImportServiceImpl(storageFacade(captured));
        String text = "#DataSourceSettings#\n"
                + "#BEGIN#\n"
                + "<data-source name=\"broken\"><jdbc-url>jdbc:mysql://localhost:3306/demo</jdbc-url></data-source>\n";

        NcxImportResponse response = service.datagripUploadFile(text);

        assertEquals(0, response.getCount());
        assertNull(response.getResult());
        assertNull(captured.get());
    }

    private static IWorkspaceStorageFacade storageFacade(AtomicReference<WorkspaceDataSource> captured) {
        return (IWorkspaceStorageFacade) Proxy.newProxyInstance(
                IWorkspaceStorageFacade.class.getClassLoader(),
                new Class<?>[] {IWorkspaceStorageFacade.class},
                (proxy, method, args) -> {
                    if ("createDataSource".equals(method.getName())) {
                        captured.set((WorkspaceDataSource) args[0]);
                        return 1L;
                    }
                    return null;
                });
    }
}
