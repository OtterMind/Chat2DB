package ai.chat2db.community.domain.api.model.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a session kill command.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbSessionKillResult {

    private Long connectionId;

    private String killType;

    private String status;

    private String sql;

    public static DbSessionKillResult killed(Long connectionId, String killType, String sql) {
        return new DbSessionKillResult(connectionId, killType, "KILLED", sql);
    }

    public static DbSessionKillResult alreadyFinished(Long connectionId, String killType, String sql) {
        return new DbSessionKillResult(connectionId, killType, "ALREADY_FINISHED", sql);
    }
}
