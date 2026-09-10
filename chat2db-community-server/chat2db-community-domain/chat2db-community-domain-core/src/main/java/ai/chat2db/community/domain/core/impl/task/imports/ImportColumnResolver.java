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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves which file column feeds which table column. Explicit mappings win; otherwise exact
 * trimmed names are preferred and a case-insensitive fallback is accepted only when unique.
 * Unmatched file columns are reported instead of silently dropping data.
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
        List<String> sourceNames = fileHeaders.stream()
                .map(ImportColumnResolver::sourceName).toList();
        Map<String, Integer> exactSources = new LinkedHashMap<>();
        for (int index = 0; index < fileHeaders.size(); index++) {
            if (exactSources.putIfAbsent(sourceNames.get(index), index) != null) {
                throw new ParamBusinessException("Duplicate import source column: " + fileHeaders.get(index));
            }
        }
        List<String> targetNames = tableColumns.stream().map(TableColumn::getName)
                .map(ImportColumnResolver::targetName).toList();
        Map<Integer, Integer> explicitTargets = new LinkedHashMap<>();
        Set<Integer> explicitSources = new HashSet<>();
        if (mappings != null) {
            for (ImportColumnMapping mapping : mappings) {
                if (mapping == null || StringUtils.isBlank(mapping.getSourceColumn())
                        || StringUtils.isBlank(mapping.getTargetColumn())) {
                    throw new ParamBusinessException("columnMappings");
                }
                Integer sourceIndex = indexOfSource(mapping.getSourceColumn(), fileHeaders, sourceNames);
                if (sourceIndex == null) {
                    throw new ParamBusinessException("columnMappings source: " + mapping.getSourceColumn());
                }
                int targetIndex = uniqueNameIndex(targetName(mapping.getTargetColumn()), targetNames,
                        "import target column");
                if (targetIndex < 0 || !explicitSources.add(sourceIndex)
                        || explicitTargets.putIfAbsent(targetIndex, sourceIndex) != null) {
                    throw new ParamBusinessException("Duplicate or invalid import column mapping");
                }
            }
        }

        List<TableColumn> resolvedColumns = new ArrayList<>();
        List<Integer> fileIndexes = new ArrayList<>();
        List<String> missingTableColumns = new ArrayList<>();
        Set<Integer> implicitSources = new HashSet<>();
        for (int tableIndex = 0; tableIndex < tableColumns.size(); tableIndex++) {
            TableColumn column = tableColumns.get(tableIndex);
            Integer sourceIndex = explicitTargets.get(tableIndex);
            if (sourceIndex == null && mappings == null) {
                int matched = uniqueNameIndex(targetNames.get(tableIndex), sourceNames,
                        "import source column");
                sourceIndex = matched < 0 ? null : matched;
                if (sourceIndex != null && !implicitSources.add(sourceIndex)) {
                    throw new ParamBusinessException("Import source column matches multiple target columns: "
                            + fileHeaders.get(sourceIndex));
                }
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

        Set<Integer> usedFileIndexes = new HashSet<>(fileIndexes);
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
            List<String> sourceNames) {
        int namedIndex = uniqueNameIndex(sourceName(source), sourceNames, "import source column");
        if (namedIndex >= 0) {
            return namedIndex;
        }
        try {
            int index = Integer.parseInt(source.trim());
            return index >= 0 && index < fileHeaders.size() ? index : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static int uniqueNameIndex(String requested, List<String> candidates, String description) {
        String expected = targetName(requested);
        int exact = -1;
        for (int index = 0; index < candidates.size(); index++) {
            if (expected.equals(targetName(candidates.get(index)))) {
                if (exact >= 0) {
                    throw ambiguous(description, requested);
                }
                exact = index;
            }
        }
        if (exact >= 0) {
            return exact;
        }
        int folded = -1;
        for (int index = 0; index < candidates.size(); index++) {
            if (expected.equalsIgnoreCase(targetName(candidates.get(index)))) {
                if (folded >= 0) {
                    throw ambiguous(description, requested);
                }
                folded = index;
            }
        }
        return folded;
    }

    private static ParamBusinessException ambiguous(String description, String requested) {
        return new ParamBusinessException("Ambiguous " + description + ": " + requested);
    }

    static String sourceName(String name) {
        String trimmed = targetName(name);
        if (!trimmed.isEmpty() && trimmed.charAt(0) == '\ufeff') {
            return trimmed.substring(1).trim();
        }
        return trimmed;
    }

    private static String targetName(String name) {
        return StringUtils.trimToEmpty(name);
    }
}
