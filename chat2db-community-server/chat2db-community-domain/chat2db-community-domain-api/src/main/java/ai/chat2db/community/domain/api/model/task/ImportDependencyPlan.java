package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** Deterministic dependency plan consumed by manifest generation and the import scheduler. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportDependencyPlan {

    private ImportPlanMode mode;

    /** Topological layers of SCC-compressed table groups. */
    private List<List<String>> layers;

    /** Cyclic components that require an explicit nullable/deferred/disabled-constraint strategy. */
    private List<List<String>> cyclicComponents;

    private List<String> selfReferencingTables;

    private Map<String, String> shardKeys;

    private boolean stagingRequired;

    private boolean cycleResolutionRequired;
}
