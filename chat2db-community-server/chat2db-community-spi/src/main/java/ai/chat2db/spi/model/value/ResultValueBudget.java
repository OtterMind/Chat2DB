package ai.chat2db.spi.model.value;

/** One V2 SQL invocation's retained UTF-8 payload budget, shared by all its result sets. */
public final class ResultValueBudget {
    public static final String PROPERTY = "chat2db.agent.v2.outputs.max-capture-bytes";
    public static final long DEFAULT_BYTES = 32L * 1024 * 1024;
    public static final String EXCEEDED = "CAPTURE_BUDGET_EXCEEDED: V2 query capture memory limit reached; this value is partial";
    private long remaining;

    public ResultValueBudget(long bytes) {
        if (bytes < 1) throw new IllegalArgumentException("V2 capture budget must be positive");
        remaining = bytes;
    }

    public static ResultValueBudget forAgent() {
        return new ResultValueBudget(Long.getLong(PROPERTY, DEFAULT_BYTES));
    }

    public long remaining() { return remaining; }

    /** Bounds a driver-specific API that already returns text, such as an EXPLAIN plan. */
    public ai.chat2db.community.domain.api.model.result.ResultCell captureText(String value) {
        return BoundedJdbcValueReader.captureText(value, this);
    }

    public boolean consume(long bytes) {
        if (bytes < 0 || bytes > remaining) return false;
        remaining -= bytes;
        return true;
    }
}
