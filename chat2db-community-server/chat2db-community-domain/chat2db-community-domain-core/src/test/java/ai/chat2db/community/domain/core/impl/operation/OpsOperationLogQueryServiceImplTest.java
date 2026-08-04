package ai.chat2db.community.domain.core.impl.operation;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationLogPageQueryRequest;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsOperationLogQueryServiceImplTest {

    @Test
    void previewDoesNotMutateStoredOperationLog() {
        String fullDdl = "x".repeat(201);
        OperationLog storedLog = new OperationLog();
        storedLog.setId(1L);
        storedLog.setDdl(fullDdl);

        OpsOperationLogQueryServiceImpl service =
                new OpsOperationLogQueryServiceImpl(storageFacade(storedLog));

        PageResponse<OperationLog> page =
                service.operationLogPreviewList(new OpsOperationLogPageQueryRequest());
        OperationLog preview = page.getData().get(0);

        assertEquals("x".repeat(200) + "...", preview.getDdl());
        assertTrue(preview.getMore());
        assertNotSame(storedLog, preview);
        assertEquals(fullDdl, storedLog.getDdl());
        assertFalse(storedLog.getMore());
        assertEquals(fullDdl, service.getOperationLog(1L).getDdl());
    }

    private static IWorkspaceStorageFacade storageFacade(OperationLog storedLog) {
        PageResponse<OperationLog> page = PageResponse.of(List.of(storedLog), 1L, 1, 10);
        return (IWorkspaceStorageFacade) Proxy.newProxyInstance(
                IWorkspaceStorageFacade.class.getClassLoader(),
                new Class<?>[]{IWorkspaceStorageFacade.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "operationLogList" -> page;
                    case "getOperationLog" -> storedLog;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
