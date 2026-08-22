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
public class DbTablespaceCreateRequest {

    @NotNull
    private Long dataSourceId;

    @NotBlank
    private String name;

    /**
     * Data-file path on the MySQL server filesystem. User-supplied and emitted verbatim; the
     * application never validates, canonicalizes, or writes this path.
     */
    @NotBlank
    private String dataFile;

    private Long fileBlockSize;
}
