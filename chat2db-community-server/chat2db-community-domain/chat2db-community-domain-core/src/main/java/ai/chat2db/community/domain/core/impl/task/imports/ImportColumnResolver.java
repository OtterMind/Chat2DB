package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportColumnMatch;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
import ai.chat2db.community.tools.exception.ParamBusinessException;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves which file column feeds which table column. Explicit mappings win; otherwise matching is
 * case-insensitive on trimmed names. Unmatched file columns are reported instead of silently
 * dropping data as the old upper-case-equality rule did.
 */
public final class ImportColumnResolver {

    /**
     * Ordered pair lists: entry {@code i} binds {@code fileValues[fileIndexes[i]]} to
     * {@code tableColumns[i]}.
     */
    public record Resolution(List<TableColumn> tableColumns, List<Integer> fileIndexes,
                             List<ImportColumnMatch> matches, List<String> missingTableColumns) {
    }

    private ImportColumnResolver() {
    }

    public static Resolution resolve(List<TableColumn> tableColumns, List<String> fileHeaders,
            ImportOptions options) {
        return resolve(tableColumns, fileHeaders, options == null ? null : options.getColumnMappings(),
                UnmappedTargetStrategy.DEFAULT);
    }

    public static Resolution resolveForSpec(List<TableColumn> tableColumns, List<String> fileHeaders,
            ImportTaskSpec spec) {
        List<ImportColumnMapping> mappings = spec.getColumnMappings();
        if (mappings == null && spec.getOptions() != null) {
            mappings = spec.getOptions().getColumnMappings();
        }
        return resolve(tableColumns, fileHeaders, mappings, spec.getUnmappedTarget());
    }

    public static void validateForImport(List<TableColumn> columns, Resolution resolution, ImportTaskSpec spec) {
        if (resolution.fileIndexes().stream().noneMatch(java.util.Objects::nonNull)) {
            throw new ParamBusinessException("At least one import column mapping is required");
        }
        for (TableColumn column : columns) {
            if (resolution.missingTableColumns().contains(column.getName())
                    && Integer.valueOf(0).equals(column.getNullable())
                    && !Boolean.TRUE.equals(column.getAutoIncrement())
                    && (spec.getUnmappedTarget() == UnmappedTargetStrategy.NULL || column.getDefaultValue() == null)) {
                throw new ParamBusinessException("Required import column is unmapped: " + column.getName());
            }
        }
    }

    private static Resolution resolve(List<TableColumn> tableColumns, List<String> fileHeaders,
            List<ImportColumnMapping> mappings, UnmappedTargetStrategy unmappedTarget) {
        Map<String, Integer> byNormalizedName = new LinkedHashMap<>();
        for (int index = 0; index < fileHeaders.size(); index++) {
            if (byNormalizedName.putIfAbsent(normalize(fileHeaders.get(index)), index) != null) {
                throw new ParamBusinessException("Duplicate import source column: " + fileHeaders.get(index));
            }
        }
        Map<String, Integer> explicitTargets = new LinkedHashMap<>();
        java.util.Set<Integer> explicitSources = new java.util.HashSet<>();
        java.util.Set<String> knownTargets = tableColumns.stream().map(column -> normalize(column.getName()))
                .collect(java.util.stream.Collectors.toSet());
        if (mappings != null) {
            for (ImportColumnMapping mapping : mappings) {
                if (mapping == null || StringUtils.isBlank(mapping.getSourceColumn())
                        || StringUtils.isBlank(mapping.getTargetColumn())) {
                    throw new ParamBusinessException("columnMappings");
                }
                Integer sourceIndex = indexOfSource(mapping.getSourceColumn().trim(), fileHeaders,
                        byNormalizedName);
                if (sourceIndex == null) {
                    throw new ParamBusinessException("columnMappings source: " + mapping.getSourceColumn());
                }
                String target = normalize(mapping.getTargetColumn());
                if (!knownTargets.contains(target) || !explicitSources.add(sourceIndex)
                        || explicitTargets.putIfAbsent(target, sourceIndex) != null) {
                    throw new ParamBusinessException("Duplicate or invalid import column mapping");
                }
            }
        }

        List<TableColumn> resolvedColumns = new ArrayList<>();
        List<Integer> fileIndexes = new ArrayList<>();
        List<String> missingTableColumns = new ArrayList<>();
        for (TableColumn column : tableColumns) {
            Integer sourceIndex = explicitTargets.get(normalize(column.getName()));
            if (sourceIndex == null && mappings == null) {
                sourceIndex = byNormalizedName.get(normalize(column.getName()));
            }
            if (sourceIndex != null) {
                resolvedColumns.add(column);
                fileIndexes.add(sourceIndex);
            } else {
                missingTableColumns.add(column.getName());
                if (mappings != null && unmappedTarget == UnmappedTargetStrategy.NULL
                        && !Boolean.TRUE.equals(column.getAutoIncrement())) {
                    resolvedColumns.add(column);
                    fileIndexes.add(null);
                }
            }
        }

        java.util.Set<Integer> usedFileIndexes = new java.util.HashSet<>(fileIndexes);
        List<ImportColumnMatch> matches = new ArrayList<>(fileHeaders.size());
        for (int index = 0; index < fileHeaders.size(); index++) {
            String tableColumn = null;
            for (int resolved = 0; resolved < fileIndexes.size(); resolved++) {
                if (java.util.Objects.equals(fileIndexes.get(resolved), index)) {
                    tableColumn = resolvedColumns.get(resolved).getName();
                    break;
                }
            }
            matches.add(ImportColumnMatch.builder()
                    .fileColumn(fileHeaders.get(index))
                    .tableColumn(tableColumn)
                    .matched(usedFileIndexes.contains(index))
                    .build());
        }
        return new Resolution(resolvedColumns, fileIndexes, matches, missingTableColumns);
    }

    private static Integer indexOfSource(String source, List<String> fileHeaders,
            Map<String, Integer> byNormalizedName) {
        Integer namedIndex = byNormalizedName.get(normalize(source));
        if (namedIndex != null) {
            return namedIndex;
        }
        try {
            int index = Integer.parseInt(source);
            return index >= 0 && index < fileHeaders.size() ? index : null;
        } catch (NumberFormatException ignored) {
            return byNormalizedName.get(normalize(source));
        }
    }

    /**
     * Case-insensitive match on trimmed names, ignoring a leading UTF-8 BOM: commons-csv does not
     * strip it, and without this the first column of every BOM-prefixed file (including files
     * written by our own CsvSink) would never match.
     */
    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name;
        if (!trimmed.isEmpty() && trimmed.charAt(0) == '\ufeff') {
            trimmed = trimmed.substring(1);
        }
        return trimmed.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
