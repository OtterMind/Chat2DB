package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.service.ai.IAiSqlAutoExecutionPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultAiSqlAutoExecutionPolicy implements IAiSqlAutoExecutionPolicy {

    @Override
    public boolean allowNonQueryExecution(
        AiExecuteSqlRequest request,
        ConnectionProfile profile,
        List<String> sqlTypes
    ) {
        return false;
    }
}
