package ai.chat2db.plugin.mysql.constant;

import java.util.List;
import java.util.Set;

public final class MysqlActiveTransactionConstants {

    public static final String RESULT_TRX_ID = "trx_id";
    public static final String RESULT_TRX_STATE = "trx_state";
    public static final String RESULT_TRX_STARTED = "trx_started";
    public static final String RESULT_TRX_AGE_SECONDS = "trx_age_seconds";
    public static final String RESULT_TRX_ISOLATION_LEVEL = "trx_isolation_level";
    public static final String RESULT_TRX_ROWS_LOCKED = "trx_rows_locked";
    public static final String RESULT_TRX_ROWS_MODIFIED = "trx_rows_modified";
    public static final String RESULT_TRX_LOCK_STRUCTS = "trx_lock_structs";
    public static final String RESULT_TRX_MYSQL_THREAD_ID = "trx_mysql_thread_id";
    public static final String RESULT_TRX_QUERY = "trx_query";
    public static final String RESULT_PROCESS_ID = "process_id";
    public static final String RESULT_PROCESS_USER = "process_user";
    public static final String RESULT_PROCESS_HOST = "process_host";
    public static final String RESULT_PROCESS_DB = "process_db";
    public static final String RESULT_REQUESTING_LOCK_ID = "requesting_lock_id";
    public static final String RESULT_BLOCKING_LOCK_ID = "blocking_lock_id";
    public static final String RESULT_BLOCKING_TRX_ID = "blocking_trx_id";
    public static final String RESULT_REQUESTING_PS_THREAD_ID = "requesting_ps_thread_id";
    public static final String RESULT_BLOCKING_PS_THREAD_ID = "blocking_ps_thread_id";
    public static final String RESULT_BLOCKING_THREAD_ID = "blocking_thread_id";
    public static final String RESULT_BLOCKING_PROCESS_ID = "blocking_process_id";
    public static final String RESULT_BLOCKING_USER = "blocking_user";
    public static final String RESULT_BLOCKING_HOST = "blocking_host";
    public static final String RESULT_BLOCKING_DB = "blocking_db";
    public static final String RESULT_WAITING_OBJECT_SCHEMA = "waiting_object_schema";
    public static final String RESULT_WAITING_OBJECT_NAME = "waiting_object_name";
    public static final String RESULT_WAITING_INDEX_NAME = "waiting_index_name";
    public static final String RESULT_WAITING_LOCK_TYPE = "waiting_lock_type";
    public static final String RESULT_WAITING_LOCK_MODE = "waiting_lock_mode";
    public static final String RESULT_WAITING_LOCK_STATUS = "waiting_lock_status";
    public static final String RESULT_WAITING_LOCK_DATA = "waiting_lock_data";
    public static final String RESULT_BLOCKING_OBJECT_SCHEMA = "blocking_object_schema";
    public static final String RESULT_BLOCKING_OBJECT_NAME = "blocking_object_name";
    public static final String RESULT_BLOCKING_INDEX_NAME = "blocking_index_name";
    public static final String RESULT_BLOCKING_LOCK_TYPE = "blocking_lock_type";
    public static final String RESULT_BLOCKING_LOCK_MODE = "blocking_lock_mode";
    public static final String RESULT_BLOCKING_LOCK_STATUS = "blocking_lock_status";
    public static final String RESULT_BLOCKING_LOCK_DATA = "blocking_lock_data";

    public static final String SQL_ACTIVE_TRANSACTIONS =
            "SELECT t.trx_id, t.trx_state, t.trx_started, "
                    + "TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS trx_age_seconds, "
                    + "t.trx_isolation_level, t.trx_rows_locked, t.trx_rows_modified, "
                    + "t.trx_lock_structs, t.trx_mysql_thread_id, "
                    + "p.ID AS process_id, p.USER AS process_user, p.HOST AS process_host, p.DB AS process_db, "
                    + "t.trx_query, "
                    + "%s "
                    + "FROM information_schema.innodb_trx t "
                    + "LEFT JOIN information_schema.processlist p ON t.trx_mysql_thread_id = p.ID "
                    + "%s "
                    + "ORDER BY t.trx_started";

    public static final String LOCK_COLUMNS_80 =
            "w.requesting_lock_id, w.blocking_lock_id, w.blocking_trx_id, "
                    + "w.requesting_ps_thread_id, w.blocking_ps_thread_id, "
                    + "w.waiting_object_schema, w.waiting_object_name, w.waiting_index_name, "
                    + "w.waiting_lock_type, w.waiting_lock_mode, w.waiting_lock_status, w.waiting_lock_data, "
                    + "w.blocking_object_schema, w.blocking_object_name, w.blocking_index_name, "
                    + "w.blocking_lock_type, w.blocking_lock_mode, w.blocking_lock_status, w.blocking_lock_data, "
                    + "bt.trx_mysql_thread_id AS blocking_thread_id, bp.ID AS blocking_process_id, "
                    + "bp.USER AS blocking_user, bp.HOST AS blocking_host, bp.DB AS blocking_db";

    public static final String LOCK_JOIN_80 =
            "LEFT JOIN ("
                    + "SELECT dlw.REQUESTING_ENGINE_TRANSACTION_ID AS requesting_trx_id, "
                    + "dlw.REQUESTING_ENGINE_LOCK_ID AS requesting_lock_id, "
                    + "dlw.BLOCKING_ENGINE_LOCK_ID AS blocking_lock_id, "
                    + "dlw.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_trx_id, "
                    + "dlw.REQUESTING_THREAD_ID AS requesting_ps_thread_id, "
                    + "dlw.BLOCKING_THREAD_ID AS blocking_ps_thread_id, "
                    + "rl.OBJECT_SCHEMA AS waiting_object_schema, rl.OBJECT_NAME AS waiting_object_name, "
                    + "rl.INDEX_NAME AS waiting_index_name, rl.LOCK_TYPE AS waiting_lock_type, "
                    + "rl.LOCK_MODE AS waiting_lock_mode, rl.LOCK_STATUS AS waiting_lock_status, "
                    + "rl.LOCK_DATA AS waiting_lock_data, "
                    + "bl.OBJECT_SCHEMA AS blocking_object_schema, bl.OBJECT_NAME AS blocking_object_name, "
                    + "bl.INDEX_NAME AS blocking_index_name, bl.LOCK_TYPE AS blocking_lock_type, "
                    + "bl.LOCK_MODE AS blocking_lock_mode, bl.LOCK_STATUS AS blocking_lock_status, "
                    + "bl.LOCK_DATA AS blocking_lock_data "
                    + "FROM performance_schema.data_lock_waits dlw "
                    + "LEFT JOIN performance_schema.data_locks rl "
                    + "ON dlw.REQUESTING_ENGINE_LOCK_ID = rl.ENGINE_LOCK_ID "
                    + "AND dlw.ENGINE = rl.ENGINE "
                    + "LEFT JOIN performance_schema.data_locks bl "
                    + "ON dlw.BLOCKING_ENGINE_LOCK_ID = bl.ENGINE_LOCK_ID "
                    + "AND dlw.ENGINE = bl.ENGINE"
                    + ") w ON w.requesting_trx_id = t.trx_id "
                    + "LEFT JOIN information_schema.innodb_trx bt ON bt.trx_id = w.blocking_trx_id "
                    + "LEFT JOIN information_schema.processlist bp ON bt.trx_mysql_thread_id = bp.ID";

    public static final String LOCK_COLUMNS_57 =
            "w.requesting_lock_id, w.blocking_lock_id, w.blocking_trx_id, "
                    + "NULL AS requesting_ps_thread_id, NULL AS blocking_ps_thread_id, "
                    + "NULL AS waiting_object_schema, w.waiting_object_name, w.waiting_index_name, "
                    + "w.waiting_lock_type, w.waiting_lock_mode, NULL AS waiting_lock_status, w.waiting_lock_data, "
                    + "NULL AS blocking_object_schema, w.blocking_object_name, w.blocking_index_name, "
                    + "w.blocking_lock_type, w.blocking_lock_mode, NULL AS blocking_lock_status, w.blocking_lock_data, "
                    + "bt.trx_mysql_thread_id AS blocking_thread_id, bp.ID AS blocking_process_id, "
                    + "bp.USER AS blocking_user, bp.HOST AS blocking_host, bp.DB AS blocking_db";

    public static final String LOCK_JOIN_57 =
            "LEFT JOIN ("
                    + "SELECT lw.requesting_trx_id, lw.requested_lock_id AS requesting_lock_id, "
                    + "lw.blocking_lock_id, lw.blocking_trx_id, "
                    + "rl.lock_table AS waiting_object_name, rl.lock_index AS waiting_index_name, "
                    + "rl.lock_type AS waiting_lock_type, rl.lock_mode AS waiting_lock_mode, "
                    + "rl.lock_data AS waiting_lock_data, "
                    + "bl.lock_table AS blocking_object_name, bl.lock_index AS blocking_index_name, "
                    + "bl.lock_type AS blocking_lock_type, bl.lock_mode AS blocking_lock_mode, "
                    + "bl.lock_data AS blocking_lock_data "
                    + "FROM information_schema.innodb_lock_waits lw "
                    + "LEFT JOIN information_schema.innodb_locks rl ON lw.requested_lock_id = rl.lock_id "
                    + "LEFT JOIN information_schema.innodb_locks bl ON lw.blocking_lock_id = bl.lock_id"
                    + ") w ON w.requesting_trx_id = t.trx_id "
                    + "LEFT JOIN information_schema.innodb_trx bt ON bt.trx_id = w.blocking_trx_id "
                    + "LEFT JOIN information_schema.processlist bp ON bt.trx_mysql_thread_id = bp.ID";

    public static final String LOCK_COLUMNS_UNAVAILABLE =
            "NULL AS requesting_lock_id, NULL AS blocking_lock_id, NULL AS blocking_trx_id, "
                    + "NULL AS requesting_ps_thread_id, NULL AS blocking_ps_thread_id, "
                    + "NULL AS waiting_object_schema, NULL AS waiting_object_name, NULL AS waiting_index_name, "
                    + "NULL AS waiting_lock_type, NULL AS waiting_lock_mode, NULL AS waiting_lock_status, "
                    + "NULL AS waiting_lock_data, "
                    + "NULL AS blocking_object_schema, NULL AS blocking_object_name, NULL AS blocking_index_name, "
                    + "NULL AS blocking_lock_type, NULL AS blocking_lock_mode, NULL AS blocking_lock_status, "
                    + "NULL AS blocking_lock_data, "
                    + "NULL AS blocking_thread_id, NULL AS blocking_process_id, "
                    + "NULL AS blocking_user, NULL AS blocking_host, NULL AS blocking_db";

    public static final String SQL_PROBE_MYSQL80_LOCKS =
            "SELECT 1 FROM performance_schema.data_lock_waits WHERE 1 = 0";
    public static final String SQL_PROBE_MYSQL57_LOCKS =
            "SELECT 1 FROM information_schema.innodb_lock_waits WHERE 1 = 0";
    public static final String SQL_CONNECTION_INSPECTION =
            "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO\n"
                    + "FROM information_schema.PROCESSLIST\n"
                    + "WHERE ID = %d;";

    public static final String ERROR_CODE_PROCESS_PRIVILEGE_REQUIRED =
            "mysql.activeTransaction.processPrivilegeRequired";
    public static final int MYSQL_ERROR_TABLE_ACCESS_DENIED = 1142;
    public static final int MYSQL_ERROR_TABLE_NOT_FOUND = 1146;
    public static final int MYSQL_ERROR_SPECIFIC_ACCESS_DENIED = 1227;
    public static final Set<Integer> LOCK_METADATA_UNAVAILABLE_ERROR_CODES = Set.of(
            MYSQL_ERROR_TABLE_ACCESS_DENIED,
            MYSQL_ERROR_TABLE_NOT_FOUND,
            MYSQL_ERROR_SPECIFIC_ACCESS_DENIED
    );
    public static final String PROCESS_PRIVILEGE_MESSAGE_MARKER = "PROCESS";
    public static final List<String> LOCK_METADATA_UNAVAILABLE_MESSAGE_MARKERS = List.of(
            "DATA_LOCK",
            "INNODB_LOCK",
            "PERFORMANCE_SCHEMA",
            "COMMAND DENIED",
            "DOESN'T EXIST",
            "DOES NOT EXIST",
            PROCESS_PRIVILEGE_MESSAGE_MARKER
    );
    public static final String LOCK_JOIN_UNAVAILABLE = "";
    public static final String EMPTY_ERROR_MESSAGE = "";
    public static final String QUALIFIED_NAME_SEPARATOR = ".";

    private MysqlActiveTransactionConstants() {
    }
}
