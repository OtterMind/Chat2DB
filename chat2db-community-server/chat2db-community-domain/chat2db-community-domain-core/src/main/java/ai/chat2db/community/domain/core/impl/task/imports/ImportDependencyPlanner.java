package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportAdmissionPolicy;
import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Builds a deterministic SCC-compressed dependency plan before manifest generation. */
public final class ImportDependencyPlanner {

    private static final Comparator<String> TABLE_ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(
            Comparator.naturalOrder());

    private ImportDependencyPlanner() {
    }

    public static ImportDependencyPlan plan(Collection<String> requestedTables,
            Collection<ImportTableDependency> dependencies, Map<String, String> requestedShardKeys,
            String admissionVerdict, ImportAdmissionPolicy policy, boolean trustedSource) {
        Set<String> tables = new TreeSet<>(TABLE_ORDER);
        if (requestedTables != null) {
            requestedTables.stream().filter(StringUtils::isNotBlank).map(String::trim).forEach(tables::add);
        }
        List<ImportTableDependency> edges = dependencies == null ? List.of() : dependencies.stream()
                .filter(ImportDependencyPlanner::valid)
                .filter(edge -> containsExact(tables, parentNode(edge))
                        && containsExact(tables, childNode(edge)))
                .toList();

        Map<String, Set<String>> graph = new LinkedHashMap<>();
        tables.forEach(table -> graph.put(table, new TreeSet<>(TABLE_ORDER)));
        Set<String> selfReferences = new TreeSet<>(TABLE_ORDER);
        for (ImportTableDependency edge : edges) {
            String parent = canonical(tables, parentNode(edge));
            String child = canonical(tables, childNode(edge));
            if (parent.equals(child)) {
                selfReferences.add(parent);
            } else {
                graph.get(parent).add(child);
            }
        }

        List<List<String>> components = stronglyConnectedComponents(graph);
        List<List<String>> cycles = components.stream().filter(component -> component.size() > 1).toList();
        List<List<String>> layers = topologicalLayers(graph, components);
        Map<String, String> shardKeys = normalizedShardKeys(tables, requestedShardKeys);
        ImportAdmissionPolicy effectivePolicy = policy == null ? ImportAdmissionPolicy.STRICT : policy;
        boolean forbidden = ImportParallelAdmission.FORBIDDEN.equalsIgnoreCase(admissionVerdict);
        boolean stagingRequired = !trustedSource;
        ImportPlanMode mode;
        if (forbidden) {
            mode = effectivePolicy == ImportAdmissionPolicy.STRICT
                    ? ImportPlanMode.REJECTED : ImportPlanMode.SERIAL_SAFE;
        } else if (stagingRequired) {
            mode = ImportPlanMode.STAGING_FIRST;
        } else if (hasCommonShardKey(tables, shardKeys) && cycles.isEmpty() && selfReferences.isEmpty()) {
            mode = ImportPlanMode.PARALLEL_SHARD;
        } else {
            mode = ImportPlanMode.PARALLEL_LAYER;
        }

        return ImportDependencyPlan.builder()
                .mode(mode)
                .layers(layers)
                .cyclicComponents(cycles)
                .selfReferencingTables(List.copyOf(selfReferences))
                .shardKeys(shardKeys)
                .stagingRequired(stagingRequired)
                .cycleResolutionRequired(!cycles.isEmpty() || !selfReferences.isEmpty())
                .build();
    }

    private static boolean valid(ImportTableDependency edge) {
        return edge != null && StringUtils.isNotBlank(parentNode(edge))
                && StringUtils.isNotBlank(childNode(edge));
    }

    private static String parentNode(ImportTableDependency edge) {
        return StringUtils.defaultIfBlank(edge.getParentTableKey(), edge.getParentTable()).trim();
    }

    private static String childNode(ImportTableDependency edge) {
        return StringUtils.defaultIfBlank(edge.getChildTableKey(), edge.getChildTable()).trim();
    }

    private static boolean containsExact(Set<String> values, String candidate) {
        return values.stream().anyMatch(value -> value.equals(candidate.trim()));
    }

    private static String canonical(Set<String> tables, String candidate) {
        return tables.stream().filter(table -> table.equals(candidate.trim())).findFirst()
                .orElse(candidate.trim());
    }

    private static Map<String, String> normalizedShardKeys(Set<String> tables, Map<String, String> requested) {
        Map<String, String> result = new LinkedHashMap<>();
        if (requested == null) {
            return result;
        }
        for (String table : tables) {
            requested.entrySet().stream()
                    .filter(entry -> table.equals(entry.getKey()) && StringUtils.isNotBlank(entry.getValue()))
                    .findFirst().ifPresent(entry -> result.put(table, entry.getValue().trim()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean hasCommonShardKey(Set<String> tables, Map<String, String> shardKeys) {
        if (tables.isEmpty() || shardKeys.size() != tables.size()) {
            return false;
        }
        return shardKeys.values().stream().map(String::toLowerCase).distinct().count() == 1L;
    }

    private static List<List<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
        Tarjan tarjan = new Tarjan(graph);
        for (String table : graph.keySet()) {
            if (!tarjan.indexes.containsKey(table)) {
                tarjan.visit(table);
            }
        }
        tarjan.components.sort(Comparator.comparing(component -> component.get(0), TABLE_ORDER));
        return tarjan.components.stream().map(List::copyOf).toList();
    }

    private static List<List<String>> topologicalLayers(Map<String, Set<String>> graph,
            List<List<String>> components) {
        Map<String, Integer> componentByTable = new HashMap<>();
        for (int index = 0; index < components.size(); index++) {
            for (String table : components.get(index)) {
                componentByTable.put(table, index);
            }
        }
        Map<Integer, Set<Integer>> dag = new HashMap<>();
        int[] indegree = new int[components.size()];
        for (int index = 0; index < components.size(); index++) {
            dag.put(index, new HashSet<>());
        }
        graph.forEach((parent, children) -> children.forEach(child -> {
            int from = componentByTable.get(parent);
            int to = componentByTable.get(child);
            if (from != to && dag.get(from).add(to)) {
                indegree[to]++;
            }
        }));

        Set<Integer> ready = new TreeSet<>(Comparator.comparing(index -> components.get(index).get(0), TABLE_ORDER));
        for (int index = 0; index < indegree.length; index++) {
            if (indegree[index] == 0) {
                ready.add(index);
            }
        }
        List<List<String>> layers = new ArrayList<>();
        int visited = 0;
        while (!ready.isEmpty()) {
            List<Integer> current = List.copyOf(ready);
            ready.clear();
            List<String> layer = new ArrayList<>();
            for (int component : current) {
                layer.addAll(components.get(component));
                visited++;
                for (int child : dag.get(component)) {
                    if (--indegree[child] == 0) {
                        ready.add(child);
                    }
                }
            }
            layer.sort(TABLE_ORDER);
            layers.add(List.copyOf(layer));
        }
        if (visited != components.size()) {
            throw new IllegalStateException("SCC condensation must produce an acyclic graph");
        }
        return List.copyOf(layers);
    }

    private static final class Tarjan {
        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> indexes = new HashMap<>();
        private final Map<String, Integer> lowLinks = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<List<String>> components = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        private void visit(String table) {
            indexes.put(table, nextIndex);
            lowLinks.put(table, nextIndex++);
            stack.push(table);
            onStack.add(table);
            for (String child : graph.get(table)) {
                if (!indexes.containsKey(child)) {
                    visit(child);
                    lowLinks.put(table, Math.min(lowLinks.get(table), lowLinks.get(child)));
                } else if (onStack.contains(child)) {
                    lowLinks.put(table, Math.min(lowLinks.get(table), indexes.get(child)));
                }
            }
            if (lowLinks.get(table).equals(indexes.get(table))) {
                List<String> component = new ArrayList<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(table));
                component.sort(TABLE_ORDER);
                components.add(component);
            }
        }
    }
}
