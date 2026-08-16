package ai.chat2db.community.web.api.model.request.sql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SqlFormatRequest {

    @NotBlank
    @Size(max = 50000)
    private String sql;

    private String dbType;
}
