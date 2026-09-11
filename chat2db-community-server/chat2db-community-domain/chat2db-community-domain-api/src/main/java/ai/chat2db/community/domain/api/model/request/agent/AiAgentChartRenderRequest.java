package ai.chat2db.community.domain.api.model.request.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AiAgentChartRenderRequest(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_-]{0,99}") String resultId,
        @NotBlank @Size(max = 32) String chartType,
        @Size(max = 256) String xField,
        @Size(max = 256) String yField,
        @Size(max = 200) String title,
        @Valid @Size(max = 8) List<@NotNull AiAgentChartSeriesRequest> series) {

    public record AiAgentChartSeriesRequest(
            @NotBlank @Size(max = 256) String field,
            @NotBlank @Pattern(regexp = "Column|Line|AreaLine|Scatter") String chartType,
            @NotBlank @Pattern(regexp = "left|right") String axisPosition) { }
}
