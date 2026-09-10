
package ai.chat2db.community.domain.api.model.request.datasource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class DbDatabaseCreateRequest {

    @NotNull
    private Long dataSourceId;

    @NotBlank
    private String name;

    private String newName;

    private String comment;

    private String charset;

    private String collation;

}
