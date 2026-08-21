package ai.chat2db.community.domain.api.service.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;

import java.util.List;

/**
 * Decides whether an AI tool may automatically execute non-query SQL.
 */
public interface IAiSqlAutoExecutionPolicy {

    boolean allowNonQueryExecution(
        AiExecuteSqlRequest request,
        ConnectionProfile profile,
        List<String> sqlTypes
    );
}
