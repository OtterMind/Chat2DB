package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.request.db.DbTablePageQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbTablePinService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbTableServiceImplPinnedTest {

    private static IDbTablePinService pinServiceReturning(List<String> pinnedNames) {
        return (IDbTablePinService) Proxy.newProxyInstance(
                DbTableServiceImplPinnedTest.class.getClassLoader(),
                new Class<?>[]{IDbTablePinService.class},
                (proxy, method, args) -> {
                    if ("queryPinTables".equals(method.getName())) {
                        return pinnedNames;
                    }
                    return null;
                });
    }

    private static Table table(String name) {
        Table table = new Table();
        table.setName(name);
        return table;
    }

    @SuppressWarnings("unchecked")
    private static List<Table> addPinnedTables(DbTableServiceImpl service, List<Table> tables,
                                               DbTablePageQueryRequest request) throws Exception {
        Method method = DbTableServiceImpl.class.getDeclaredMethod("addPinnedTables", List.class,
                DbTablePageQueryRequest.class);
        method.setAccessible(true);
        return (List<Table>) method.invoke(service, tables, request);
    }

    @Test
    void pinnedTableOnFirstPageIsNotDuplicated() throws Exception {
        DbTableServiceImpl service = new DbTableServiceImpl(pinServiceReturning(List.of("users")));
        DbTablePageQueryRequest request = new DbTablePageQueryRequest();
        request.setPageNo(1);
        request.setSearchKey(null);

        List<Table> page = new ArrayList<>(List.of(table("users"), table("orders")));
        List<Table> result = addPinnedTables(service, page, request);

        assertEquals(2, result.size(), "pinned table must not be appended twice");
        assertEquals("users", result.get(0).getName());
        assertTrue(result.get(0).isPinned());
        assertEquals(1, result.stream().filter(t -> "users".equals(t.getName())).count());
    }

    @Test
    void pinnedTableNotOnPageKeepsPageUntouched() throws Exception {
        DbTableServiceImpl service = new DbTableServiceImpl(pinServiceReturning(List.of("audit_log")));
        DbTablePageQueryRequest request = new DbTablePageQueryRequest();
        request.setPageNo(1);
        request.setSearchKey(null);

        List<Table> page = new ArrayList<>(List.of(table("users"), table("orders")));
        List<Table> result = addPinnedTables(service, page, request);

        assertEquals(2, result.size());
    }

    @Test
    void searchKeySkipsPinnedMerge() throws Exception {
        DbTableServiceImpl service = new DbTableServiceImpl(pinServiceReturning(List.of("users")));
        DbTablePageQueryRequest request = new DbTablePageQueryRequest();
        request.setPageNo(1);
        request.setSearchKey("use");

        List<Table> page = new ArrayList<>(List.of(table("users")));
        List<Table> result = addPinnedTables(service, page, request);

        assertEquals(1, result.size());
    }
}
