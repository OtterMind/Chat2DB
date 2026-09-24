package ai.chat2db.community.sqlx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlxDataSourceReaderTest {

    /** Records the calls and answers the two datasource reads the reader is allowed to use. */
    private static final class Stub implements InvocationHandler {

        private final List<String> calls = new ArrayList<>();
        private final List<Long> requestedPasswords = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("queryDisplayDataSourceById".equals(name)) {
                calls.add(name);
                requestedPasswords.add((Boolean) args[1] ? (Long) args[0] : null);
                return dataSource((Long) args[0], "decrypted-secret");
            }
            if ("exportDataSources".equals(name)) {
                // The raw record keeps the password encrypted; the reader must not use this route.
                calls.add(name);
                return List.of(dataSource(1L, "AES-GCM-CIPHERTEXT"));
            }
            if ("toString".equals(name)) {
                return "stub";
            }
            throw new UnsupportedOperationException(name);
        }
    }

    private static WorkspaceDataSource dataSource(Long id, String password) {
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setId(id);
        dataSource.setAlias("datasource " + id);
        dataSource.setType("MYSQL");
        dataSource.setHost("localhost");
        dataSource.setPort("3306");
        dataSource.setUser("root");
        dataSource.setPassword(password);
        return dataSource;
    }

    private static IDbWorkspaceDataSourceService service(Stub stub) {
        return (IDbWorkspaceDataSourceService) Proxy.newProxyInstance(
                SqlxDataSourceReaderTest.class.getClassLoader(),
                new Class<?>[] {IDbWorkspaceDataSourceService.class},
                stub);
    }

    @Test
    void readsCredentialsThroughTheDecryptedDatasourceView() {
        Stub stub = new Stub();
        List<WorkspaceDataSource> found = new SqlxDataSourceReader(service(stub)).read(List.of(7L));

        assertEquals(List.of("queryDisplayDataSourceById"), stub.calls);
        assertEquals(List.of(7L), stub.requestedPasswords, "the password must be requested, not masked");
        assertEquals(1, found.size());
        assertEquals("decrypted-secret", found.get(0).getPassword());
    }

    @Test
    void skipsABlankSelectionWithoutReadingAnything() {
        Stub stub = new Stub();
        SqlxDataSourceReader reader = new SqlxDataSourceReader(service(stub));

        assertTrue(reader.read(List.of()).isEmpty());
        assertTrue(reader.read(null).isEmpty());
        assertEquals(List.of(), stub.calls);
    }

    @Test
    void skipsANullIdInsideTheSelection() {
        Stub stub = new Stub();
        List<WorkspaceDataSource> found =
                new SqlxDataSourceReader(service(stub)).read(java.util.Arrays.asList(null, 2L));

        assertEquals(List.of("queryDisplayDataSourceById"), stub.calls);
        assertEquals(1, found.size());
        assertEquals(2L, found.get(0).getId());
    }

    @Test
    void reportsNothingWhenTheDatasourceIsGone() {
        IDbWorkspaceDataSourceService missing = (IDbWorkspaceDataSourceService) Proxy.newProxyInstance(
                SqlxDataSourceReaderTest.class.getClassLoader(),
                new Class<?>[] {IDbWorkspaceDataSourceService.class},
                (proxy, method, args) -> {
                    if ("queryDisplayDataSourceById".equals(method.getName())) {
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "stub";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        List<WorkspaceDataSource> found = new SqlxDataSourceReader(missing).read(List.of(42L));
        assertTrue(found.isEmpty());
        assertFalse(found.contains(null));
    }
}
