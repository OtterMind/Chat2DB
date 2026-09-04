package ai.chat2db.community.domain.api.model.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Transaction mode reported for a console-scoped transaction session.
 */
public enum TransactionMode {
    AUTO("auto"),
    MANUAL("manual");

    private final String value;

    TransactionMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TransactionMode fromValue(String value) {
        for (TransactionMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported transaction mode: " + value);
    }
}
