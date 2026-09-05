package ai.chat2db.plugin.mysql.constant;

public final class MysqlLockConstants {

    public static final String SQL_DATA_LOCKS_80 =
            "SELECT ENGINE_LOCK_ID, ENGINE_TRANSACTION_ID, THREAD_ID, EVENT_ID, OBJECT_SCHEMA, OBJECT_NAME, "
                    + "INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA "
                    + "FROM performance_schema.data_locks ORDER BY ENGINE_LOCK_ID";
    public static final String SQL_DATA_LOCK_WAITS_80 =
            "SELECT REQUESTING_ENGINE_LOCK_ID, REQUESTING_ENGINE_TRANSACTION_ID, "
                    + "REQUESTING_THREAD_ID, REQUESTING_EVENT_ID, BLOCKING_ENGINE_LOCK_ID, "
                    + "BLOCKING_ENGINE_TRANSACTION_ID, BLOCKING_THREAD_ID, BLOCKING_EVENT_ID "
                    + "FROM performance_schema.data_lock_waits";
    public static final String SQL_DATA_LOCKS_57 =
            "SELECT lock_id, lock_trx_id, lock_mode, lock_type, lock_table, lock_index, "
                    + "lock_space, lock_page, lock_rec, lock_data "
                    + "FROM information_schema.innodb_locks ORDER BY lock_id";
    public static final String SQL_DATA_LOCK_WAITS_57 =
            "SELECT requesting_trx_id, requested_lock_id, blocking_trx_id, blocking_lock_id "
                    + "FROM information_schema.innodb_lock_waits";
    public static final String SQL_METADATA_LOCKS =
            "SELECT OBJECT_TYPE, OBJECT_SCHEMA, OBJECT_NAME, OBJECT_INSTANCE_BEGIN, "
                    + "LOCK_TYPE, LOCK_DURATION, LOCK_STATUS, OWNER_THREAD_ID, OWNER_EVENT_ID "
                    + "FROM performance_schema.metadata_locks "
                    + "ORDER BY OBJECT_TYPE, OBJECT_SCHEMA, OBJECT_NAME, OWNER_THREAD_ID, OWNER_EVENT_ID";
    public static final String SQL_METADATA_LOCK_WAITS =
            "SELECT object_schema AS OBJECT_SCHEMA, object_name AS OBJECT_NAME, "
                    + "waiting_thread_id AS WAITING_THREAD_ID, waiting_pid AS WAITING_PID, "
                    + "waiting_account AS WAITING_ACCOUNT, waiting_lock_type AS WAITING_LOCK_TYPE, "
                    + "waiting_lock_duration AS WAITING_LOCK_DURATION, waiting_query AS WAITING_QUERY, "
                    + "blocking_thread_id AS BLOCKING_THREAD_ID, blocking_pid AS BLOCKING_PID, "
                    + "blocking_account AS BLOCKING_ACCOUNT, blocking_lock_type AS BLOCKING_LOCK_TYPE, "
                    + "blocking_lock_duration AS BLOCKING_LOCK_DURATION "
                    + "FROM sys.schema_table_lock_waits WHERE waiting_thread_id <> blocking_thread_id "
                    + "ORDER BY object_schema, object_name, waiting_thread_id";
    public static final String SQL_SESSION_INFO_PERFORMANCE_SCHEMA =
            "SELECT th.THREAD_ID, th.PROCESSLIST_ID, th.PROCESSLIST_USER, th.PROCESSLIST_HOST, "
                    + "th.PROCESSLIST_DB, th.PROCESSLIST_COMMAND, th.PROCESSLIST_TIME, "
                    + "th.PROCESSLIST_STATE, th.PROCESSLIST_INFO, t.trx_id, t.trx_mysql_thread_id, "
                    + "t.trx_state, t.trx_query "
                    + "FROM performance_schema.threads th "
                    + "LEFT JOIN information_schema.innodb_trx t ON t.trx_mysql_thread_id = th.PROCESSLIST_ID "
                    + "WHERE th.PROCESSLIST_ID IS NOT NULL";
    public static final String SQL_SESSION_INFO_57 =
            "SELECT t.trx_id, t.trx_mysql_thread_id, t.trx_state, p.USER, p.HOST, p.DB, t.trx_query "
                    + "FROM information_schema.innodb_trx t "
                    + "LEFT JOIN information_schema.processlist p ON t.trx_mysql_thread_id = p.ID";
    public static final String SQL_PROBE_DATA_LOCKS_80 =
            "SELECT 1 FROM performance_schema.data_locks LIMIT 1";

    public static final String SQL_STATE_TABLE_NOT_FOUND = "42S02";
    public static final String SQL_STATE_INVALID_AUTHORIZATION = "28000";
    public static final int MYSQL_ERROR_DATABASE_ACCESS_DENIED = 1044;
    public static final int MYSQL_ERROR_COMMAND_DENIED = 1142;
    public static final int MYSQL_ERROR_COLUMN_COMMAND_DENIED = 1143;
    public static final int MYSQL_ERROR_TABLE_NOT_FOUND = 1146;
    public static final int MYSQL_ERROR_SPECIFIC_ACCESS_DENIED = 1227;

    private MysqlLockConstants() {
    }
}
