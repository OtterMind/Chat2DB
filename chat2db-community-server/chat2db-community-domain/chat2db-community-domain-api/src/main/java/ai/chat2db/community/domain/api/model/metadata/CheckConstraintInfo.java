package ai.chat2db.community.domain.api.model.metadata;

import java.io.Serializable;
import lombok.Data;

@Data
public class CheckConstraintInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String expression;
    private Boolean enforced;
    private String tableName;
    private String schemaName;
    private String databaseName;
    private String editStatus;
}
