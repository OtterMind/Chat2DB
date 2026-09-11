package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultAiSqlAutoExecutionPolicyTest {

    @Test
    void communityAndLocalKeepManualConfirmationForNonQuerySql() {
        assertFalse(new DefaultAiSqlAutoExecutionPolicy().allowNonQueryExecution(
            new AiExecuteSqlRequest(), new ConnectionProfile(), List.of("INSERT")));
    }
}
