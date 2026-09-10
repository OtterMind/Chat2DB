package ai.chat2db.community.web.api.model.request.driver;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class JdbcDriverRequest {
    @NotBlank
    String jdbcDriverClass;
    @NotBlank
    String dbType;

    @NotEmpty
    List<@NotBlank String> jdbcDriver;
}
