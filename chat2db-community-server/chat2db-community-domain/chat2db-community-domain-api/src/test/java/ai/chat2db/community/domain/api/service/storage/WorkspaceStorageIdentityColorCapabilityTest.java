package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.tools.exception.storage.UnsupportedStorageCapabilityException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceStorageIdentityColorCapabilityTest {

    @Test
    void providerWithoutPartialUpdateFailsClosed() {
        IWorkspaceStorage storage = new IWorkspaceStorage() {
        };

        UnsupportedStorageCapabilityException exception = assertThrows(
                UnsupportedStorageCapabilityException.class,
                () -> storage.updateDataSourceIdentityColor(1L, "#ABCDEF"));

        assertTrue(exception.getMessage().contains("updateDataSourceIdentityColor"));
    }

    @Test
    void facadeWithoutPartialUpdateFailsClosed() {
        IWorkspaceStorageFacade facade = (IWorkspaceStorageFacade) Proxy.newProxyInstance(
                IWorkspaceStorageFacade.class.getClassLoader(),
                new Class<?>[]{IWorkspaceStorageFacade.class},
                (proxy, method, args) -> method.isDefault()
                        ? InvocationHandler.invokeDefault(proxy, method, args)
                        : null);

        UnsupportedStorageCapabilityException exception = assertThrows(
                UnsupportedStorageCapabilityException.class,
                () -> facade.updateDataSourceIdentityColor(1L, "#ABCDEF"));

        assertTrue(exception.getMessage().contains("updateDataSourceIdentityColor"));
    }
}
