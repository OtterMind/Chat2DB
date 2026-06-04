package ai.chat2db.server.domain.api.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateVirtualFKParam {

    @NotNull
    private Long id;

    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private String comment;

    private String tableName;

    private String columnName;

    private String referencedTable;

    private String referencedColumnName;

    private String vkName;
}
