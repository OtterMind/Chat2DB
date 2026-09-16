package ai.chat2db.community.domain.api.model.task;

/** CSV import modes. Request fields remain strings containing these uppercase names. */
public enum TaskExecutionMode {
    STANDARD,
    FAST;

    /** Only an explicit FAST selects parallel execution; other values retain ordinary behavior. */
    public static boolean isFast(String mode) {
        return FAST.name().equals(mode);
    }
}
