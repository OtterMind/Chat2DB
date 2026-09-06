package ai.chat2db.community.domain.api.model.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbExplainCapability {

    private String databaseType;

    private String serverVersion;

    private boolean explainJsonSupported;

    private boolean explainAnalyzeSupported;
}
