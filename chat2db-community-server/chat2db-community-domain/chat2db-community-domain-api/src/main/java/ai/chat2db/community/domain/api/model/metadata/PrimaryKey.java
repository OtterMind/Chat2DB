package ai.chat2db.community.domain.api.model.metadata;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PrimaryKey {

    @JsonAlias({"TABLE_CAT"})
    private String databaseName;

    @JsonAlias({"TABLE_SCHEM"})
    private String schemaName;

    @JsonAlias({"TABLE_NAME"})
    private String tableName;

    @JsonAlias({"COLUMN_NAME"})
    private String columnName;

    @JsonAlias({"PK_NAME"})
    private String primaryKeyName;

    @JsonAlias({"KEY_SEQ"})
    private Integer keySeq;

}
