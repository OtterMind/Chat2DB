package ai.chat2db.community.web.api.model.response.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@AllArgsConstructor
public class AgentTaskScheduleCronPreviewResponse {
    private List<Date> nextRuns;
}
