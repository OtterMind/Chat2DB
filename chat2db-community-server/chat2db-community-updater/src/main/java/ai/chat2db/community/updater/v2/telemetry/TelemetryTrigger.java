package ai.chat2db.community.updater.v2.telemetry;

import java.util.Locale;

/** Why an update check ran; reported as {@code data.trigger} so automatic and manual checks can be compared. */
public enum TelemetryTrigger {

    STARTUP("startup"),
    SCHEDULED("scheduled"),
    MANUAL("manual"),
    UNKNOWN("unknown");

    private final String wireName;

    TelemetryTrigger(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static TelemetryTrigger fromWire(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TelemetryTrigger trigger : values()) {
            if (trigger.wireName.equals(normalized)) {
                return trigger;
            }
        }
        return UNKNOWN;
    }
}
