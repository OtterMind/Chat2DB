package ai.chat2db.community.domain.api.model.task;

public final class TaskConstants {

    public static final int PENDING_PROGRESS = 0;

    public static final int STARTED_PROGRESS = 1;

    public static final int MAX_RUNNING_PROGRESS = 99;

    public static final int COMPLETED_PROGRESS = 100;

    public static final int DEFAULT_PAGE_SIZE = 20;

    public static final int DEFAULT_EVENT_LIMIT = 200;

    public static final int MAX_EVENT_LIMIT = 1000;

    public static final int MAX_PUBLIC_ERROR_MESSAGE_LENGTH = 512;

    public static final long EXPORT_LOG_ROW_INTERVAL = 1_000L;

    public static final String EXPORTED_ROWS_DETAIL_KEY = "exportedRows";

    public static final String EXPORTED_TABLES_DETAIL_KEY = "exportedTables";

    public static final String TOTAL_TABLES_DETAIL_KEY = "totalTables";

    public static final String TABLE_NAME_DETAIL_KEY = "tableName";

    public static final String OBJECT_TYPE_DETAIL_KEY = "objectType";

    public static final String FILE_FORMAT_DETAIL_KEY = "format";

    public static final String FILE_NAME_DETAIL_KEY = "fileName";

    public static final String ARTIFACT_ID_DETAIL_KEY = "artifactId";

    public static final String ARTIFACT_ROLE_DETAIL_KEY = "role";

    public static final String ARTIFACT_TEMPORARY_PATH_DETAIL_KEY = "temporaryPath";

    public static final String ARTIFACT_TARGET_PATH_DETAIL_KEY = "targetPath";

    public static final String EXPORT_SCOPE_DETAIL_KEY = "scope";

    public static final String ERROR_CODE_DETAIL_KEY = "errorCode";

    public static final String ERROR_REASON_DETAIL_KEY = "reason";

    public static final String DELETE_ACTIVE_FORBIDDEN_MESSAGE_CODE = "task.delete.activeForbidden";

    public static final String DELETE_ARTIFACT_FAILED_MESSAGE_CODE = "task.delete.artifactFailed";

    private TaskConstants() {
    }
}
