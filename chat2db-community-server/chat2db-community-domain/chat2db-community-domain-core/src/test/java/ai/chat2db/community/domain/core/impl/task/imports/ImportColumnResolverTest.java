package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.tools.exception.ParamBusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportColumnResolverTest {

    @Test
    void implicitMappingPreservesCaseDistinctQuotedColumns() {
        ImportColumnResolver.Resolution resolution = ImportColumnResolver.resolve(
                columns("ID", "id"), List.of("ID", "id"), new ImportOptions());

        assertEquals(List.of("ID", "id"), resolution.tableColumns().stream()
                .map(TableColumn::getName).toList());
        assertEquals(List.of(0, 1), resolution.fileIndexes());
    }

    @Test
    void explicitMappingUsesExactSourceAndTargetBeforeCompatibilityFallback() {
        ImportOptions options = ImportOptions.builder().columnMappings(List.of(
                new ImportColumnMapping("ID", "id"),
                new ImportColumnMapping("id", "ID"))).build();

        ImportColumnResolver.Resolution resolution = ImportColumnResolver.resolve(
                columns("ID", "id"), List.of("ID", "id"), options);

        assertEquals(List.of(1, 0), resolution.fileIndexes());
    }

    @Test
    void rejectsAmbiguousCaseInsensitiveSourceFallback() {
        ImportOptions options = ImportOptions.builder().columnMappings(List.of(
                new ImportColumnMapping("Id", "ID"))).build();

        ParamBusinessException failure = assertThrows(ParamBusinessException.class,
                () -> ImportColumnResolver.resolve(columns("ID"), List.of("ID", "id"), options));

        assertTrue(failure.getArgs()[0].toString().contains("Ambiguous import source column"));
    }

    @Test
    void rejectsAmbiguousCaseInsensitiveTargetFallback() {
        ImportOptions options = ImportOptions.builder().columnMappings(List.of(
                new ImportColumnMapping("value", "Id"))).build();

        ParamBusinessException failure = assertThrows(ParamBusinessException.class,
                () -> ImportColumnResolver.resolve(columns("ID", "id"), List.of("value"), options));

        assertTrue(failure.getArgs()[0].toString().contains("Ambiguous import target column"));
    }

    @Test
    void keepsUniqueCaseInsensitiveAndBomCompatibility() {
        ImportColumnResolver.Resolution resolution = ImportColumnResolver.resolve(
                columns("NAME"), List.of("\ufeffname"), new ImportOptions());

        assertEquals(List.of(0), resolution.fileIndexes());
        assertEquals(0, ImportColumnResolver.uniqueNameIndex("Name", List.of("name"),
                "logical dependency column"));
    }

    private static List<TableColumn> columns(String... names) {
        java.util.ArrayList<TableColumn> columns = new java.util.ArrayList<>();
        for (String name : names) {
            columns.add(TableColumn.builder().name(name).build());
        }
        return List.copyOf(columns);
    }
}
