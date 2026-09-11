package ai.chat2db.community.domain.api.model.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportTargetColumn {

    private String name;

    private String dataType;

    private boolean nullable;

    private boolean autoIncrement;

    private String defaultValue;

    private String comment;
}
