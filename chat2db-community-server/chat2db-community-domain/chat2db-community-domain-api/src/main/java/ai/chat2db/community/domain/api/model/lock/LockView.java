package ai.chat2db.community.domain.api.model.lock;

import lombok.Data;

import java.util.List;

@Data
public class LockView {

    public enum Source {
        PERFORMANCE_SCHEMA,
        INFORMATION_SCHEMA,
        UNAVAILABLE
    }

    public enum ErrorSection {
        DATA_LOCKS,
        WAITS,
        METADATA_LOCKS,
        METADATA_WAITS,
        SESSIONS
    }

    public enum LockKind {
        DATA,
        METADATA
    }

    public enum ErrorCode {
        PRIVILEGE_REQUIRED,
        UNAVAILABLE
    }

    private Long dataSourceId;
    private Source source;
    private List<DataLock> dataLocks;
    private List<LockWait> waits;
    private List<MetadataLock> metaLocks;
    private List<LockSession> sessions;
    private List<WaitChain> waitChains;
    private List<WaitChain> metadataWaitChains;
    private List<ViewError> errors;

    @Data
    public static class DataLock {
        private String lockId;
        private String transactionId;
        private String engineThreadId;
        private String eventId;
        private String objectSchema;
        private String objectName;
        private String indexName;
        private String lockType;
        private String lockMode;
        private String lockStatus;
        private String lockData;
        private String spaceId;
        private String pageId;
        private String recordId;
    }

    @Data
    public static class LockWait {
        private String waiterLockId;
        private String waiterTransactionId;
        private String waiterThreadId;
        private String waiterEventId;
        private String blockerLockId;
        private String blockerTransactionId;
        private String blockerThreadId;
        private String blockerEventId;
    }

    @Data
    public static class MetadataLock {
        private String objectType;
        private String objectSchema;
        private String objectName;
        private String objectInstanceId;
        private String lockType;
        private String lockDuration;
        private String lockStatus;
        private String ownerThreadId;
        private String ownerEventId;
        private String ownerSessionId;
        private String ownerUser;
        private String ownerHost;
        private String ownerDatabase;
        private String ownerState;
        private String ownerQuery;
        private boolean ownerSessionAvailable;
    }

    @Data
    public static class LockSession {
        private String engineThreadId;
        private String sessionId;
        private String user;
        private String host;
        private String databaseName;
        private String command;
        private String timeSeconds;
        private String state;
        private String query;
        private String transactionId;
    }

    @Data
    public static class WaitChain {
        private Long dataSourceId;
        private LockKind lockKind;
        private String lockObject;
        private String waiterTransactionId;
        private String waiterLockId;
        private String waiterThreadId;
        private String waiterEngineThreadId;
        private String waiterState;
        private String waiterUser;
        private String waiterHost;
        private String waiterDatabase;
        private String waiterQuery;
        private boolean waiterSessionAvailable;
        private int waiterMetadataLockCount;
        private String waiterLockMode;
        private String blockerTransactionId;
        private String blockerLockId;
        private String blockerThreadId;
        private String blockerEngineThreadId;
        private String blockerState;
        private String blockerUser;
        private String blockerHost;
        private String blockerDatabase;
        private String blockerQuery;
        private boolean blockerSessionAvailable;
        private int blockerMetadataLockCount;
        private String blockerLockMode;
        private boolean rootBlocker;
        private boolean cycle;
    }

    @Data
    public static class ViewError {
        private ErrorSection section;
        private ErrorCode code;
    }
}
