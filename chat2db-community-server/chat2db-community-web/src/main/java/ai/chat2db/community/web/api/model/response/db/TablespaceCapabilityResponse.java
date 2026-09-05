package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

@Data
public class TablespaceCapabilityResponse {

    private boolean manageSupported;

    private boolean renameSupported;

    private String serverVersion;
}
