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
public class DbTablespaceModifyRequest {

    @NotNull
    private Long dataSourceId;

    @NotBlank
    private String oldName;

    @NotBlank
    private String newName;
}
