package ai.chat2db.community.domain.core.impl.ncx;

import ai.chat2db.community.domain.api.model.ncx.NcxImportResponse;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskNcxImportServiceIdentityColorTest {

    @TempDir
    Path tempDir;

    @Test
    void normalizesChat2dbBackupColorBeforeStorageFacade() throws Exception {
        AtomicReference<WorkspaceDataSource> captured = new AtomicReference<>();
        TaskNcxImportServiceImpl service = new TaskNcxImportServiceImpl(storageFacade(captured));
        File backup = tempDir.resolve("datasources.json").toFile();
        Files.writeString(backup.toPath(), "[{\"identityColor\":\"  #a1b2c3  \"}]",
                StandardCharsets.UTF_8);

        NcxImportResponse response = service.chat2dbUploadFile(backup);

        assertEquals(1, response.getCount());
        assertEquals("#A1B2C3", captured.get().getIdentityColor());
    }

    @Test
    void rejectsInvalidChat2dbBackupColorBeforeStorageFacade() throws Exception {
        AtomicReference<WorkspaceDataSource> captured = new AtomicReference<>();
        TaskNcxImportServiceImpl service = new TaskNcxImportServiceImpl(storageFacade(captured));
        File backup = tempDir.resolve("invalid-datasources.json").toFile();
        Files.writeString(backup.toPath(), "[{\"identityColor\":\"blue\"}]", StandardCharsets.UTF_8);

        assertThrows(BusinessException.class, () -> service.chat2dbUploadFile(backup));
        assertNull(captured.get());
    }

    private static IWorkspaceStorageFacade storageFacade(AtomicReference<WorkspaceDataSource> captured) {
        return (IWorkspaceStorageFacade) Proxy.newProxyInstance(
                IWorkspaceStorageFacade.class.getClassLoader(),
                new Class<?>[]{IWorkspaceStorageFacade.class},
                (proxy, method, args) -> {
                    if ("createDataSource".equals(method.getName())) {
                        captured.set((WorkspaceDataSource) args[0]);
                        return 1L;
                    }
                    return null;
                });
    }
}
