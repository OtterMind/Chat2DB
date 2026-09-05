package ai.chat2db.community.domain.api.model.db;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TablespaceCapability {

    /**
     * Whether create/drop/table placement/migration is supported (MySQL 5.7.6+).
     */
    private boolean manageSupported;

    /**
     * Whether the current server supports {@code ALTER TABLESPACE ... RENAME TO} (MySQL 8.0+).
     */
    private boolean renameSupported;

    /**
     * Server version used to make the capability decision.
     */
    private String serverVersion;
}
