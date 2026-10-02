package ai.chat2db.community.domain.api.model.task;

/** Policy applied when the mandatory parallel-import admission gate finds blockers. */
public enum ImportAdmissionPolicy {
    STRICT,
    MODERATE,
    LENIENT
}
