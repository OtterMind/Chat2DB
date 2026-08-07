package ai.chat2db.community.domain.api.model.db;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TablespaceCapability {

    /**
     * Whether the current server supports {@code ALTER TABLESPACE ... RENAME TO} (MySQL 8.0+).
     */
    private boolean renameSupported;
}
