package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Normalizes legacy and multi-table import sources behind one execution contract. */
public final class ImportTaskSourceSupport {

    private static final Set<String> SOURCE_KINDS = Set.of("TRUSTED", "THIRD_PARTY");

    private static final Set<String> CYCLE_STRATEGIES = Set.of(
            "REJECT", "DEFER_CONSTRAINTS", "STAGING_TWO_PHASE");

    private static final Set<String> ERROR_POLICIES = Set.of("ABORT", "SKIP");

    private static final Set<String> SQL_EXPORTER_PROFILES = Set.of(
            "NAVICAT", "DBEAVER", "DATAGRIP", "HEIDISQL", "PHPMYADMIN", "MYSQL_WORKBENCH",
            "PGADMIN", "SSMS", "ORACLE_SQL_DEVELOPER");

    private ImportTaskSourceSupport() {
    }

    public static List<ImportTableSource> effectiveSources(ImportTaskSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("Import task specification is required");
        }
        TaskTargetSnapshot target = spec.getTarget();
        List<ImportTableSource> configured = spec.getTableSources();
        List<ImportTableSource> sources = CollectionUtils.isEmpty(configured)
                ? List.of(legacySource(spec, target))
                : configured.stream().map(source -> normalizedSource(spec, target, source)).toList();
        validateScope(spec, target, sources);
        validateControls(spec, sources);
        return List.copyOf(sources);
    }

    public static boolean isMultiTable(ImportTaskSpec spec) {
        return spec != null && CollectionUtils.isNotEmpty(spec.getTableSources());
    }

    /** Validates the legacy single-file SQL controls before a task is persisted. */
    public static void validateSqlImportControls(ImportTaskSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("Import task specification is required");
        }
        if (CollectionUtils.isNotEmpty(spec.getTableSources())
                || CollectionUtils.isNotEmpty(spec.getLogicalDependencies())
                || StringUtils.isNotBlank(spec.getCycleStrategy())
                || spec.getStagingPolicy() != null
                || spec.getValidationOptions() != null
                || spec.getFinalizationOptions() != null
                || spec.getRollbackOptions() != null
                || spec.getPerformanceSamplePercent() != null
                || spec.getConfirmedNoStrongRelations() != null) {
            throw new IllegalArgumentException(
                    "SQL file import does not accept multi-table manifest controls");
        }
        requireMember("source kind", spec.getSourceKind(), SOURCE_KINDS);
        validateSqlExporterProfile(spec.getSourceKind(), spec.getOptions(), true);
    }

    public static ImportTaskSpec specForSource(ImportTaskSpec parent, ImportTableSource source) {
        TaskTargetSnapshot parentTarget = parent.getTarget();
        return ImportTaskSpec.builder()
                .taskType(parent.getTaskType())
                .taskName(parent.getTaskName())
                .target(TaskTargetSnapshot.builder()
                        .dataSourceId(parentTarget == null ? null : parentTarget.getDataSourceId())
                        .databaseName(source.getDatabaseName())
                        .schemaName(source.getSchemaName())
                        .tableName(source.getTableName())
                        .build())
                .scope(ImportScope.TABLE)
                .sourceKind(parent.getSourceKind())
                .cycleStrategy(parent.getCycleStrategy())
                .stagingPolicy(parent.getStagingPolicy())
                .validationOptions(parent.getValidationOptions())
                .finalizationOptions(parent.getFinalizationOptions())
                .rollbackOptions(parent.getRollbackOptions())
                .performanceSamplePercent(parent.getPerformanceSamplePercent())
                .sourceFile(source.getSourceFile())
                .importFileId(source.getImportFileId())
                .displayFileName(source.getDisplayFileName())
                .format(StringUtils.defaultIfBlank(source.getFormat(), parent.getFormat()))
                .dataTimeFormat(StringUtils.defaultIfBlank(source.getDataTimeFormat(), parent.getDataTimeFormat()))
                .columnMappings(source.getColumnMappings() == null
                        ? parent.getColumnMappings() : source.getColumnMappings())
                .unmappedTarget(source.getUnmappedTarget() == null
                        ? parent.getUnmappedTarget() : source.getUnmappedTarget())
                .options(source.getOptions() == null ? parent.getOptions() : source.getOptions())
                .mode(parent.getMode())
                .confirmedNoStrongRelations(parent.getConfirmedNoStrongRelations())
                .build();
    }

    public static String tableKey(ImportTableSource source) {
        return tableKey(source.getDatabaseName(), source.getSchemaName(), source.getTableName());
    }

    public static String tableKey(String databaseName, String schemaName, String tableName) {
        return Stream.of(databaseName, schemaName, tableName)
                .filter(StringUtils::isNotBlank)
                .map(value -> value.trim().replace("\\", "\\\\").replace(".", "\\."))
                .reduce((left, right) -> left + "." + right)
                .orElse("");
    }

    private static ImportTableSource legacySource(ImportTaskSpec spec, TaskTargetSnapshot target) {
        return ImportTableSource.builder()
                .databaseName(target == null ? null : target.getDatabaseName())
                .schemaName(target == null ? null : target.getSchemaName())
                .tableName(target == null ? null : target.getTableName())
                .sourceFile(spec.getSourceFile())
                .importFileId(spec.getImportFileId())
                .displayFileName(spec.getDisplayFileName())
                .format(spec.getFormat())
                .dataTimeFormat(spec.getDataTimeFormat())
                .columnMappings(spec.getColumnMappings())
                .unmappedTarget(spec.getUnmappedTarget())
                .options(spec.getOptions())
                .build();
    }

    private static ImportTableSource normalizedSource(ImportTaskSpec spec, TaskTargetSnapshot target,
            ImportTableSource source) {
        if (source == null) {
            throw new IllegalArgumentException("Import table source cannot be null");
        }
        return ImportTableSource.builder()
                .databaseName(StringUtils.defaultIfBlank(source.getDatabaseName(),
                        target == null ? null : target.getDatabaseName()))
                .schemaName(StringUtils.defaultIfBlank(source.getSchemaName(),
                        target == null ? null : target.getSchemaName()))
                .tableName(StringUtils.trimToNull(source.getTableName()))
                .sourceFile(source.getSourceFile())
                .importFileId(source.getImportFileId())
                .displayFileName(source.getDisplayFileName())
                .format(StringUtils.defaultIfBlank(source.getFormat(), spec.getFormat()))
                .dataTimeFormat(StringUtils.defaultIfBlank(source.getDataTimeFormat(), spec.getDataTimeFormat()))
                .columnMappings(source.getColumnMappings())
                .unmappedTarget(source.getUnmappedTarget())
                .options(source.getOptions())
                .build();
    }

    private static void validateScope(ImportTaskSpec spec, TaskTargetSnapshot target,
            List<ImportTableSource> sources) {
        String scope = ImportScope.normalize(spec.getScope());
        if (target == null) {
            throw new IllegalArgumentException("Import target is required");
        }
        if (ImportScope.TABLE.equals(scope) && sources.size() != 1) {
            throw new IllegalArgumentException("TABLE import scope accepts exactly one table source");
        }
        if (ImportScope.TABLE.equals(scope) && StringUtils.isBlank(target.getTableName())) {
            throw new IllegalArgumentException("TABLE import scope requires a target table");
        }
        if ((ImportScope.SCHEMA.equals(scope) || ImportScope.DATABASE.equals(scope))
                && StringUtils.isBlank(target.getDatabaseName())) {
            throw new IllegalArgumentException(scope + " import scope requires a target database");
        }
        if (ImportScope.SCHEMA.equals(scope) && StringUtils.isBlank(target.getSchemaName())) {
            throw new IllegalArgumentException("SCHEMA import scope requires a target schema");
        }
        Set<String> keys = new HashSet<>();
        for (ImportTableSource source : sources) {
            if (StringUtils.isBlank(source.getTableName())) {
                throw new IllegalArgumentException("Every import table source requires a target table");
            }
            String key = tableKey(source);
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Duplicate import table source: " + key);
            }
            if (!sameIdentifier(target.getDatabaseName(), source.getDatabaseName())) {
                throw new IllegalArgumentException("Import table source is outside the selected database: " + key);
            }
            if ((ImportScope.TABLE.equals(scope) || ImportScope.SCHEMA.equals(scope))
                    && !sameIdentifier(target.getSchemaName(), source.getSchemaName())) {
                throw new IllegalArgumentException("Import table source is outside the selected schema: " + key);
            }
            if (ImportScope.TABLE.equals(scope)
                    && !sameIdentifier(target.getTableName(), source.getTableName())) {
                throw new IllegalArgumentException("Import table source is outside the selected table: " + key);
            }
        }
    }

    private static void validateControls(ImportTaskSpec spec, List<ImportTableSource> sources) {
        requireMember("source kind", spec.getSourceKind(), SOURCE_KINDS);
        requireMember("cycle strategy", spec.getCycleStrategy(), CYCLE_STRATEGIES);
        Integer samplePercent = spec.getPerformanceSamplePercent();
        if (samplePercent != null && (samplePercent < 1 || samplePercent > 100)) {
            throw new IllegalArgumentException("Import performance sample percent must be between 1 and 100");
        }
        if (samplePercent != null && !Boolean.TRUE.equals(spec.getRollbackOptions() == null
                ? null : spec.getRollbackOptions().getRehearsal())) {
            throw new IllegalArgumentException("Import performance sample percent requires rollback rehearsal");
        }
        boolean thirdParty = "THIRD_PARTY".equalsIgnoreCase(StringUtils.trimToEmpty(spec.getSourceKind()));
        for (ImportTableSource source : sources) {
            ImportOptions options = source.getOptions() == null ? spec.getOptions() : source.getOptions();
            validateSqlExporterProfile(spec.getSourceKind(), options, thirdParty && isSqlSource(spec, source));
            if (options != null) {
                requireMember("row error policy", options.getOnError(), ERROR_POLICIES);
                if (options.getMaxErrors() != null && options.getMaxErrors() < 0) {
                    throw new IllegalArgumentException("Import maxErrors cannot be negative");
                }
            }
        }
    }

    private static void validateSqlExporterProfile(String sourceKind, ImportOptions options,
            boolean requireForThirdParty) {
        String exporterProfile = options == null ? null : options.getSqlExporterProfile();
        if (StringUtils.isNotBlank(exporterProfile)) {
            requireMember("SQL exporter profile", normalizeSqlExporterProfile(exporterProfile),
                    SQL_EXPORTER_PROFILES);
        }
        if (requireForThirdParty
                && "THIRD_PARTY".equalsIgnoreCase(StringUtils.trimToEmpty(sourceKind))
                && StringUtils.isBlank(exporterProfile)) {
            throw new IllegalArgumentException(
                    "THIRD_PARTY SQL imports require an explicit SQL exporter profile");
        }
    }

    private static boolean isSqlSource(ImportTaskSpec spec, ImportTableSource source) {
        return "SQL".equalsIgnoreCase(StringUtils.trimToEmpty(source.getFormat()))
                || "SQL_FILE_IMPORT".equalsIgnoreCase(StringUtils.trimToEmpty(spec.getTaskType()));
    }

    private static String normalizeSqlExporterProfile(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return switch (normalized) {
            case "MYSQLWORKBENCH", "WORKBENCH" -> "MYSQL_WORKBENCH";
            case "PHP_MY_ADMIN" -> "PHPMYADMIN";
            case "ORACLE_SQLDEVELOPER", "SQL_DEVELOPER" -> "ORACLE_SQL_DEVELOPER";
            default -> normalized;
        };
    }

    private static void requireMember(String label, String value, Set<String> supported) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!supported.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported import " + label + ": " + value);
        }
    }

    private static boolean sameIdentifier(String expected, String actual) {
        return StringUtils.equals(StringUtils.trimToNull(expected), StringUtils.trimToNull(actual));
    }
}
