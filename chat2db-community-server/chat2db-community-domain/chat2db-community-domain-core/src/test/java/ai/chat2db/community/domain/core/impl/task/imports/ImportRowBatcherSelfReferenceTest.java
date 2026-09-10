package ai.chat2db.community.domain.core.impl.task.imports;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportRowBatcherSelfReferenceTest {

    @Test
    void quotedCaseDistinctTablesDoNotTriggerSelfReferenceWarning() throws Exception {
        ResultSet key = importedKey("app", "public", "users", "app", "public", "Users");

        assertFalse(ImportRowBatcher.shouldWarnForSelfReference(key));
    }

    @Test
    void exactSelfReferenceWithNullDefaultScopeStillTriggersWarning() throws Exception {
        ResultSet key = importedKey(null, null, "Tree", null, null, "Tree");

        assertTrue(ImportRowBatcher.shouldWarnForSelfReference(key));
    }

    @Test
    void sameTableNameInDifferentPhysicalScopesDoesNotTriggerSelfReferenceWarning() throws Exception {
        assertFalse(ImportRowBatcher.shouldWarnForSelfReference(
                importedKey("app", "tenant_a", "Tree", "app", "tenant_b", "Tree")));
        assertFalse(ImportRowBatcher.shouldWarnForSelfReference(
                importedKey("app_a", null, "Tree", "app_b", null, "Tree")));
        assertFalse(ImportRowBatcher.shouldWarnForSelfReference(
                importedKey(null, null, "Tree", "app", null, "Tree")));
    }

    private static ResultSet importedKey(String foreignCatalog, String foreignSchema, String foreignTable,
            String primaryCatalog, String primarySchema, String primaryTable) {
        Map<String, String> values = new HashMap<>();
        values.put("FKTABLE_CAT", foreignCatalog);
        values.put("FKTABLE_SCHEM", foreignSchema);
        values.put("FKTABLE_NAME", foreignTable);
        values.put("PKTABLE_CAT", primaryCatalog);
        values.put("PKTABLE_SCHEM", primarySchema);
        values.put("PKTABLE_NAME", primaryTable);
        return (ResultSet) Proxy.newProxyInstance(ImportRowBatcherSelfReferenceTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                    if ("getString".equals(method.getName()) && arguments != null
                            && arguments.length == 1 && arguments[0] instanceof String label) {
                        return values.get(label);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
