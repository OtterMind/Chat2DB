package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportDependencyResolverTest {

    @Test
    void mergesImportedKeysAndLogicalEdgesWithPhysicalMetadataTakingPrecedence() {
        List<ImportTableSource> sources = List.of(source("orders"), source("order_items"), source("audit"));
        ImportTableDependency duplicateLogical = edge("orders", "id", "order_items", "order_id");
        ImportTableDependency logicalAudit = edge("orders", "id", "audit", "order_id");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .logicalDependencies(List.of(duplicateLogical, logicalAudit)).build();
        ImportDependencyResolver resolver = new ImportDependencyResolver(source ->
                "order_items".equals(source.getTableName()) ? List.of(importedKey()) : List.of());

        List<ImportTableDependency> dependencies = resolver.resolve(spec, sources);

        assertEquals(2, dependencies.size());
        ImportTableDependency physical = dependencies.stream()
                .filter(item -> "order_items".equals(item.getChildTable())).findFirst().orElseThrow();
        assertFalse(physical.isLogical());
        assertEquals("fk_order_items_orders", physical.getConstraintName());
        assertEquals((short) 1, physical.getKeySequence());
        assertEquals((short) 5, physical.getDeferrability());
        assertEquals("app.public.orders", physical.getParentTableKey());
        assertEquals("app.public.order_items", physical.getChildTableKey());
        assertTrue(dependencies.stream().anyMatch(item -> item.isLogical()
                && "audit".equals(item.getChildTable())));
    }

    @Test
    void rejectsAnAmbiguousUnqualifiedLogicalEndpoint() {
        List<ImportTableSource> sources = List.of(
                ImportTableSource.builder().databaseName("app").schemaName("one").tableName("orders").build(),
                ImportTableSource.builder().databaseName("app").schemaName("two").tableName("orders").build(),
                source("audit"));
        ImportTaskSpec spec = ImportTaskSpec.builder().logicalDependencies(List.of(
                edge("orders", "id", "audit", "order_id"))).build();

        assertThrows(IllegalArgumentException.class,
                () -> new ImportDependencyResolver(source -> List.of()).resolve(spec, sources));
    }

    @Test
    void rejectsALogicalDependencyWithoutBothColumns() {
        List<ImportTableSource> sources = List.of(source("orders"), source("audit"));
        ImportTaskSpec spec = ImportTaskSpec.builder().logicalDependencies(List.of(
                ImportTableDependency.builder().parentTable("orders").parentColumn("id")
                        .childTable("audit").logical(true).build())).build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ImportDependencyResolver(source -> List.of()).resolve(spec, sources));

        assertTrue(failure.getMessage().contains("tables and columns"));
    }

    @Test
    void retainsPhysicalDependencyWhenParentTableIsOutsideTheImportSet() {
        ImportTaskSpec spec = ImportTaskSpec.builder().build();
        ForeignKeyInfo external = importedKey("tenants", "id", "orders", "tenant_id",
                "fk_orders_tenant");

        List<ImportTableDependency> dependencies = new ImportDependencyResolver(
                source -> List.of(external)).resolve(spec, List.of(source("orders")));

        assertEquals(1, dependencies.size());
        ImportTableDependency dependency = dependencies.get(0);
        assertEquals("app.public.tenants", dependency.getParentTableKey());
        assertEquals("app.public.orders", dependency.getChildTableKey());
        assertFalse(dependency.isLogical());
    }

    @Test
    void rejectsUnnamedCompositeForeignKeyWhenConstraintIdentityCannotBeProven() {
        ForeignKeyInfo first = importedKey("orders", "tenant_id", "order_items", "tenant_id", null);
        ForeignKeyInfo second = importedKey("orders", "id", "order_items", "order_id", null);
        second.setKeySeq((short) 2);
        ImportDependencyResolver resolver = new ImportDependencyResolver(source ->
                "order_items".equals(source.getTableName()) ? List.of(first, second) : List.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(ImportTaskSpec.builder().build(),
                        List.of(source("orders"), source("order_items"))));

        assertTrue(failure.getMessage().contains("unnamed composite foreign key"));
        assertTrue(failure.getMessage().contains("stable constraint metadata"));
    }

    @Test
    void acceptsOneUnnamedSingleColumnForeignKey() {
        ForeignKeyInfo unnamed = importedKey("orders", "id", "order_items", "order_id", null);
        ImportDependencyResolver resolver = new ImportDependencyResolver(source ->
                "order_items".equals(source.getTableName()) ? List.of(unnamed) : List.of());

        List<ImportTableDependency> dependencies = resolver.resolve(ImportTaskSpec.builder().build(),
                List.of(source("orders"), source("order_items")));

        assertEquals(1, dependencies.size());
        assertEquals(null, dependencies.get(0).getConstraintName());
    }

    @Test
    void resolvesPhysicalDependencyBetweenQuotedTablesThatDifferOnlyByCase() {
        ForeignKeyInfo quoted = importedKey("Users", "id", "users", "parent_id",
                "fk_users_Users");
        ImportDependencyResolver resolver = new ImportDependencyResolver(source ->
                "users".equals(source.getTableName()) ? List.of(quoted) : List.of());

        List<ImportTableDependency> dependencies = resolver.resolve(ImportTaskSpec.builder().build(),
                List.of(source("Users"), source("users")));

        assertEquals(1, dependencies.size());
        assertEquals("app.public.Users", dependencies.get(0).getParentTableKey());
        assertEquals("app.public.users", dependencies.get(0).getChildTableKey());
    }

    private static ImportTableSource source(String table) {
        return ImportTableSource.builder().databaseName("app").schemaName("public").tableName(table).build();
    }

    private static ImportTableDependency edge(String parentTable, String parentColumn,
            String childTable, String childColumn) {
        return ImportTableDependency.builder().parentTable(parentTable).parentColumn(parentColumn)
                .childTable(childTable).childColumn(childColumn).logical(true).build();
    }

    private static ForeignKeyInfo importedKey() {
        return importedKey("orders", "id", "order_items", "order_id",
                "fk_order_items_orders");
    }

    private static ForeignKeyInfo importedKey(String parentTable, String parentColumn,
            String childTable, String childColumn, String constraintName) {
        ForeignKeyInfo info = new ForeignKeyInfo();
        info.setPkTableCat("app");
        info.setPkTableSchem("public");
        info.setPkTableName(parentTable);
        info.setPkColumnName(parentColumn);
        info.setFkTableCat("app");
        info.setFkTableSchem("public");
        info.setFkTableName(childTable);
        info.setFkColumnName(childColumn);
        info.setFkName(constraintName);
        info.setKeySeq((short) 1);
        info.setDeferrability((short) 5);
        return info;
    }
}
