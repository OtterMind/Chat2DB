package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.exception.ai.AiToolException;
import ai.chat2db.community.domain.api.exception.ai.AiToolInvalidArgumentException;
import ai.chat2db.community.domain.api.exception.ai.AiToolMetadataQueryException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlConfirmationRequiredException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlExecutionException;
import org.springframework.stereotype.Component;

@Component
public class AiToolFailureSummaryMapper {

    public String summaryFor(AiToolException e) {
        if (e instanceof AiToolInvalidArgumentException) {
            return "Invalid tool arguments.";
        }
        if (e instanceof AiToolSqlConfirmationRequiredException) {
            return "SQL requires manual confirmation before execution.";
        }
        if (e instanceof AiToolSqlExecutionException) {
            return "SQL execution failed.";
        }
        if (e instanceof AiToolMetadataQueryException) {
            return "Database metadata query failed.";
        }
        return "Tool execution failed.";
    }
}
