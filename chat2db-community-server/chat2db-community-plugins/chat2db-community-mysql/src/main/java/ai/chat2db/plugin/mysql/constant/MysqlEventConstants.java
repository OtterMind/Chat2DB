package ai.chat2db.plugin.mysql.constant;

import ai.chat2db.spi.constant.SQLConstants;

public final class MysqlEventConstants {

    public static final String SQL_SCHEDULER_STATE = "SHOW VARIABLES LIKE 'event_scheduler'";
    public static final String SQL_EVENT_COUNT =
            "SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = ?";
    public static final String SQL_EVENTS =
            "SELECT EVENT_SCHEMA, EVENT_NAME, DEFINER, TIME_ZONE, EVENT_TYPE, EXECUTE_AT, INTERVAL_VALUE, "
                    + "INTERVAL_FIELD, STARTS, ENDS, STATUS, ON_COMPLETION, EVENT_COMMENT, EVENT_DEFINITION "
                    + "FROM information_schema.EVENTS WHERE EVENT_SCHEMA = ? ORDER BY EVENT_NAME";
    public static final String SQL_DROP_EVENT = "DROP EVENT IF EXISTS %s";
    public static final String SQL_ALTER_EVENT_ENABLED = "ALTER EVENT %s %s";
    public static final String EVENT_ENABLED = "ENABLE";
    public static final String EVENT_DISABLED = "DISABLE";
    public static final String SCHEDULER_ON = "ON";
    public static final String SCHEDULER_OFF = "OFF";
    public static final String QUALIFIED_NAME_SEPARATOR = ".";
    public static final String FIELD_EVENT_NAME = "eventName";
    public static final String FIELD_DEFINER = "definer";
    public static final String FIELD_TIME_ZONE = "timeZone";
    public static final String FIELD_EVENT_TYPE = "eventType";
    public static final String FIELD_EXECUTE_AT = "executeAt";
    public static final String FIELD_INTERVAL_VALUE = "intervalValue";
    public static final String FIELD_INTERVAL_FIELD = "intervalField";
    public static final String FIELD_STARTS = "starts";
    public static final String FIELD_ENDS = "ends";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_ON_COMPLETION = "onCompletion";
    public static final String FIELD_COMMENT = "comment";
    public static final String FIELD_DEFINITION = "definition";
    public static final String FIELD_SCHEDULER_ENABLED = "schedulerEnabled";
    public static final String FIELD_EVENT_COUNT = "eventCount";
    public static final String RESULT_EVENT_SCHEMA = "EVENT_SCHEMA";
    public static final String RESULT_EVENT_NAME = "EVENT_NAME";
    public static final String RESULT_DEFINER = "DEFINER";
    public static final String RESULT_TIME_ZONE = "TIME_ZONE";
    public static final String RESULT_EVENT_TYPE = "EVENT_TYPE";
    public static final String RESULT_EXECUTE_AT = "EXECUTE_AT";
    public static final String RESULT_INTERVAL_VALUE = "INTERVAL_VALUE";
    public static final String RESULT_INTERVAL_FIELD = "INTERVAL_FIELD";
    public static final String RESULT_STARTS = "STARTS";
    public static final String RESULT_ENDS = "ENDS";
    public static final String RESULT_STATUS = "STATUS";
    public static final String RESULT_ON_COMPLETION = "ON_COMPLETION";
    public static final String RESULT_EVENT_COMMENT = "EVENT_COMMENT";
    public static final String RESULT_EVENT_DEFINITION = "EVENT_DEFINITION";
    public static final String RESULT_CREATE_EVENT = "Create Event";
    public static final String EVENT_EXPORT_TITLE = "-- ----------------------------" + SQLConstants.LINE_SEPARATOR
            + "-- Event structure for event %s" + SQLConstants.LINE_SEPARATOR
            + "-- ----------------------------";

    private MysqlEventConstants() {
    }
}
