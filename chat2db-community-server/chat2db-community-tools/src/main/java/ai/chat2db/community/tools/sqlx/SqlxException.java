package ai.chat2db.community.tools.sqlx;

/**
 * Failure of a SQLX command line operation, carrying a stable code for the desktop shell.
 */
public class SqlxException extends RuntimeException {

    private final String code;

    public SqlxException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SqlxException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
