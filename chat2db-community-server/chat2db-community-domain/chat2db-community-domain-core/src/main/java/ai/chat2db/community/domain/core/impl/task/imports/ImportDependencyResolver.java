package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges JDBC foreign-key metadata with explicitly configured logical relationships. */
public final class ImportDependencyResolver {

    private static final Comparator<String> IDENTIFIER_ORDER = Comparator.nullsFirst(
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

    private final ForeignKeyReader foreignKeyReader;

    public ImportDependencyResolver() {
        this(ImportDependencyResolver::readImportedKeys);
    }

    ImportDependencyResolver(ForeignKeyReader foreignKeyReader) {
        this.foreignKeyReader = foreignKeyReader;
    }

    public List<ImportTableDependency> resolve(ImportTaskSpec spec, List<ImportTableSource> sources) {
        SourceIndex index = new SourceIndex(sources);
        Map<EdgeIdentity, ImportTableDependency> merged = new LinkedHashMap<>();
        for (ImportTableSource child : sources) {
            List<ForeignKeyInfo> importedKeys = foreignKeyReader.read(child);
            if (importedKeys == null) {
                continue;
            }
            validateUnnamedPhysicalKeys(child, importedKeys);
            for (ForeignKeyInfo foreignKey : importedKeys) {
                ImportTableDependency dependency = physicalDependency(index, child, foreignKey);
                if (dependency != null) {
                    merged.putIfAbsent(edgeKey(dependency), dependency);
                }
            }
        }
        if (spec.getLogicalDependencies() != null) {
            for (ImportTableDependency configured : spec.getLogicalDependencies()) {
                ImportTableDependency dependency = logicalDependency(index, configured);
                merged.putIfAbsent(edgeKey(dependency), dependency);
            }
        }
        return merged.values().stream().sorted(Comparator
                .comparing(ImportTableDependency::getParentTableKey, IDENTIFIER_ORDER)
                .thenComparing(ImportTableDependency::getChildTableKey, IDENTIFIER_ORDER)
                .thenComparing(ImportTableDependency::getKeySequence,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ImportTableDependency::getParentColumn, IDENTIFIER_ORDER)
                .thenComparing(ImportTableDependency::getChildColumn, IDENTIFIER_ORDER))
                .toList();
    }

    private static void validateUnnamedPhysicalKeys(ImportTableSource currentChild,
            List<ForeignKeyInfo> importedKeys) {
        Map<DependencyEndpoints, List<ForeignKeyInfo>> unnamedByEndpoints = new LinkedHashMap<>();
        for (ForeignKeyInfo foreignKey : importedKeys) {
            if (foreignKey == null || StringUtils.isNotBlank(foreignKey.getFkName())
                    || StringUtils.isAnyBlank(foreignKey.getPkTableName(), foreignKey.getFkTableName())) {
                continue;
            }
            String childKey = ImportTaskSourceSupport.tableKey(
                    StringUtils.defaultIfBlank(foreignKey.getFkTableCat(), currentChild.getDatabaseName()),
                    StringUtils.defaultIfBlank(foreignKey.getFkTableSchem(), currentChild.getSchemaName()),
                    foreignKey.getFkTableName());
            if (!childKey.equals(ImportTaskSourceSupport.tableKey(currentChild))) {
                continue;
            }
            String parentKey = ImportTaskSourceSupport.tableKey(
                    StringUtils.defaultIfBlank(foreignKey.getPkTableCat(), currentChild.getDatabaseName()),
                    StringUtils.defaultIfBlank(foreignKey.getPkTableSchem(), currentChild.getSchemaName()),
                    foreignKey.getPkTableName());
            unnamedByEndpoints.computeIfAbsent(new DependencyEndpoints(parentKey, childKey),
                    ignored -> new ArrayList<>())
                    .add(foreignKey);
        }
        for (List<ForeignKeyInfo> candidates : unnamedByEndpoints.values()) {
            boolean ambiguous = candidates.size() > 1
                    || candidates.stream().anyMatch(key -> key.getKeySeq() != 1);
            if (ambiguous) {
                ForeignKeyInfo first = candidates.get(0);
                throw new IllegalArgumentException("Cannot safely identify unnamed composite foreign key between "
                        + first.getFkTableName() + " and " + first.getPkTableName()
                        + "; provide stable constraint metadata before importing");
            }
        }
    }

    private ImportTableDependency physicalDependency(SourceIndex index, ImportTableSource currentChild,
            ForeignKeyInfo foreignKey) {
        if (foreignKey == null || StringUtils.isAnyBlank(foreignKey.getPkTableName(),
                foreignKey.getFkTableName())) {
            return null;
        }
        ImportTableSource child = index.findPhysical(foreignKey.getFkTableCat(), foreignKey.getFkTableSchem(),
                foreignKey.getFkTableName(), currentChild.getDatabaseName(), currentChild.getSchemaName());
        if (child == null || !ImportTaskSourceSupport.tableKey(child)
                .equals(ImportTaskSourceSupport.tableKey(currentChild))) {
            return null;
        }
        ImportTableSource parent = index.findPhysical(foreignKey.getPkTableCat(), foreignKey.getPkTableSchem(),
                foreignKey.getPkTableName(), currentChild.getDatabaseName(), currentChild.getSchemaName());
        if (parent == null) {
            parent = ImportTableSource.builder()
                    .databaseName(StringUtils.defaultIfBlank(foreignKey.getPkTableCat(),
                            currentChild.getDatabaseName()))
                    .schemaName(StringUtils.defaultIfBlank(foreignKey.getPkTableSchem(),
                            currentChild.getSchemaName()))
                    .tableName(foreignKey.getPkTableName())
                    .build();
        }
        return dependency(parent, foreignKey.getPkColumnName(), child, foreignKey.getFkColumnName(),
                foreignKey.getFkName(), Short.valueOf(foreignKey.getKeySeq()),
                Short.valueOf(foreignKey.getDeferrability()), false);
    }

    private ImportTableDependency logicalDependency(SourceIndex index, ImportTableDependency configured) {
        if (configured == null || StringUtils.isAnyBlank(configured.getParentTable(),
                configured.getParentColumn(), configured.getChildTable(), configured.getChildColumn())) {
            throw new IllegalArgumentException("Logical dependency requires parent/child tables and columns");
        }
        ImportTableSource parent = index.requireLogical(configured.getParentDatabaseName(),
                configured.getParentSchemaName(), configured.getParentTable(), "parent");
        ImportTableSource child = index.requireLogical(configured.getChildDatabaseName(),
                configured.getChildSchemaName(), configured.getChildTable(), "child");
        return dependency(parent, configured.getParentColumn(), child, configured.getChildColumn(),
                configured.getConstraintName(), configured.getKeySequence(), configured.getDeferrability(), true);
    }

    private static ImportTableDependency dependency(ImportTableSource parent, String parentColumn,
            ImportTableSource child, String childColumn, String constraintName, Short keySequence,
            Short deferrability, boolean logical) {
        return ImportTableDependency.builder()
                .parentDatabaseName(parent.getDatabaseName())
                .parentSchemaName(parent.getSchemaName())
                .parentTable(parent.getTableName())
                .parentColumn(StringUtils.trimToNull(parentColumn))
                .parentTableKey(ImportTaskSourceSupport.tableKey(parent))
                .childDatabaseName(child.getDatabaseName())
                .childSchemaName(child.getSchemaName())
                .childTable(child.getTableName())
                .childColumn(StringUtils.trimToNull(childColumn))
                .childTableKey(ImportTaskSourceSupport.tableKey(child))
                .constraintName(StringUtils.trimToNull(constraintName))
                .keySequence(keySequence)
                .deferrability(deferrability)
                .logical(logical)
                .build();
    }

    private static EdgeIdentity edgeKey(ImportTableDependency dependency) {
        return new EdgeIdentity(dependency.getParentTableKey(), dependency.getParentColumn(),
                dependency.getChildTableKey(), dependency.getChildColumn());
    }

    private static List<ForeignKeyInfo> readImportedKeys(ImportTableSource source) {
        return Chat2DBContext.getDbMetaData().getImportedKeys(Chat2DBContext.getConnection(),
                new TableMetadataRequest(source.getDatabaseName(), source.getSchemaName(), source.getTableName()));
    }

    @FunctionalInterface
    interface ForeignKeyReader {
        List<ForeignKeyInfo> read(ImportTableSource source);
    }

    private static final class SourceIndex {
        private final List<ImportTableSource> sources;

        private SourceIndex(List<ImportTableSource> sources) {
            if (sources == null || sources.isEmpty()) {
                throw new IllegalArgumentException("Dependency resolution requires at least one table source");
            }
            this.sources = List.copyOf(sources);
        }

        private ImportTableSource findPhysical(String databaseName, String schemaName, String tableName,
                String fallbackDatabase, String fallbackSchema) {
            return find(databaseName, schemaName, tableName, fallbackDatabase, fallbackSchema, false, "physical");
        }

        private ImportTableSource requireLogical(String databaseName, String schemaName, String tableName,
                String endpoint) {
            ImportTableSource result = find(databaseName, schemaName, tableName, null, null, true, endpoint);
            if (result == null) {
                throw new IllegalArgumentException("Logical dependency " + endpoint
                        + " table is not selected for import: " + tableName);
            }
            return result;
        }

        private ImportTableSource find(String databaseName, String schemaName, String tableName,
                String fallbackDatabase, String fallbackSchema, boolean failOnAmbiguous, String endpoint) {
            if (StringUtils.isBlank(tableName)) {
                return null;
            }
            String database = StringUtils.defaultIfBlank(databaseName, fallbackDatabase);
            String schema = StringUtils.defaultIfBlank(schemaName, fallbackSchema);
            List<ImportTableSource> matches = new ArrayList<>();
            for (ImportTableSource source : sources) {
                if (!sameIdentifier(tableName, source.getTableName())) {
                    continue;
                }
                if (StringUtils.isNotBlank(database)
                        && !sameIdentifier(database, source.getDatabaseName())) {
                    continue;
                }
                if (StringUtils.isNotBlank(schema)
                        && !sameIdentifier(schema, source.getSchemaName())) {
                    continue;
                }
                matches.add(source);
            }
            if (matches.size() > 1 || (failOnAmbiguous && matches.size() != 1)) {
                if (matches.isEmpty()) {
                    return null;
                }
                throw new IllegalArgumentException("Ambiguous " + endpoint + " dependency table: " + tableName);
            }
            return matches.isEmpty() ? null : matches.get(0);
        }
    }

    private static boolean sameIdentifier(String expected, String actual) {
        return StringUtils.equals(StringUtils.trimToNull(expected), StringUtils.trimToNull(actual));
    }

    private record DependencyEndpoints(String parentTableKey, String childTableKey) {
    }

    private record EdgeIdentity(String parentTableKey, String parentColumn,
                                String childTableKey, String childColumn) {
    }
}
