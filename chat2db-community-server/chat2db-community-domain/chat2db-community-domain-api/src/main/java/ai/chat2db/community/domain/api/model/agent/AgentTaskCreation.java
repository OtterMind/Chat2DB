package ai.chat2db.community.domain.api.model.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskCreation {

    private AgentTask task;

    private AgentRun initialRun;
}
