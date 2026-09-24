package ai.chat2db.community.tools.sqlx;

/**
 * How the settings page shows one Chat2DB datasource in the SQLX import table.
 * <p>
 * A state is a hint for the user, not a promise: SQLX writes the connection only when the import runs.
 * The reason and detail fields reuse the skip codes the import report already carries.
 */
public class SqlxDatasourceState {

    /** SQLX already holds a connection with the same engine and target. */
    public static final String STATE_IMPORTED = "imported";
    /** The record maps cleanly and SQLX does not hold it yet. */
    public static final String STATE_READY = "ready";
    /** SQLX has no worker for this engine. */
    public static final String STATE_UNSUPPORTED = "unsupported";
    /** The engine is supported, but the saved record lacks a field the import needs. */
    public static final String STATE_INCOMPLETE = "incomplete";

    private Long id;

    private String state = STATE_INCOMPLETE;

    private String reason;

    private String detail;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
