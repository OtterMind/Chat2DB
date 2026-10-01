package ai.chat2db.community.domain.api.model.task;

/** Execution plan selected after admission and dependency analysis. */
public enum ImportPlanMode {
    PARALLEL_SHARD,
    PARALLEL_LAYER,
    STAGING_FIRST,
    SERIAL_SAFE,
    REJECTED
}
