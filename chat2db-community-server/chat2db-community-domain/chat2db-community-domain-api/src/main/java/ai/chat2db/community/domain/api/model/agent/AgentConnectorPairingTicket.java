package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

import java.util.Date;

@Data
public class AgentConnectorPairingTicket {
    private String pairingId;
    private String pollToken;
    private String userCode;
    private Date expiresAt;
    private Integer pollAfterMs;
}
