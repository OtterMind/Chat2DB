package ai.chat2db.community.web.api.model.request.driver;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class JdbcDriverDeleteRequest {
    @NotBlank
    private String dbType;

    @NotEmpty
    private List<@NotBlank String> jdbcDriver;
}
