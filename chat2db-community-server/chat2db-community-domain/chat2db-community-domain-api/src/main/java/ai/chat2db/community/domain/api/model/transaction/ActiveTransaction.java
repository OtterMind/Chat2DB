package ai.chat2db.community.domain.api.model.transaction;

import lombok.Data;

import java.sql.Timestamp;

@Data
public class ActiveTransaction {

    public enum QueryState {
        VISIBLE,
        UNAVAILABLE
    }

    public enum SessionState {
        LIVE,
        DISAPPEARED_OR_HIDDEN
    }

    public enum LockMetadataState {
        AVAILABLE,
        UNAVAILABLE
    }

    public enum LockMetadataSource {
        MYSQL_80_PERFORMANCE_SCHEMA,
        MYSQL_57_INFORMATION_SCHEMA
    }

    private String trxId;
    private String state;
    private Timestamp startedAt;
    private Long ageSeconds;
    private String isolationLevel;
    private Long rowsLocked;
    private Long rowsModified;
    private Long lockStructs;
    private Long threadId;
    private String user;
    private String host;
    private String db;
    private String query;
    private QueryState queryState;
    private Boolean sessionAvailable;
    private SessionState sessionState;
    private Boolean canOpenSession;
    private String connectionInspectionSql;
    private String waitingLockId;
    private String blockingLockId;
    private String blockingTrxId;
    private Long waitingPerformanceSchemaThreadId;
    private Long blockingPerformanceSchemaThreadId;
    private Long blockingThreadId;
    private Boolean blockingSessionAvailable;
    private Boolean canOpenBlockingSession;
    private String blockingConnectionInspectionSql;
    private String blockingUser;
    private String blockingHost;
    private String blockingDb;
    private String waitingObject;
    private String waitingIndex;
    private String waitingLockType;
    private String waitingLockMode;
    private String waitingLockStatus;
    private String waitingLockData;
    private String blockingObject;
    private String blockingIndex;
    private String blockingLockType;
    private String blockingLockMode;
    private String blockingLockStatus;
    private String blockingLockData;
    private Boolean lockWaitAvailable;
    private LockMetadataState lockMetadataState;
    private LockMetadataSource lockMetadataSource;
}
