package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.exception.ai.AiToolException;
import ai.chat2db.community.domain.api.exception.ai.AiToolInvalidArgumentException;
import ai.chat2db.community.domain.api.exception.ai.AiToolMetadataQueryException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlConfirmationRequiredException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlExecutionException;
import org.springframework.stereotype.Component;

@Component
public class AiToolErrorCodeMapper {

    public String errorCodeFor(AiToolException e) {
        if (e instanceof AiToolInvalidArgumentException) {
            return "INVALID_ARGUMENT";
        }
        if (e instanceof AiToolSqlConfirmationRequiredException) {
            return "SQL_REQUIRES_MANUAL_CONFIRMATION";
        }
        if (e instanceof AiToolSqlExecutionException) {
            return "SQL_EXECUTION_FAILED";
        }
        if (e instanceof AiToolMetadataQueryException) {
            return "METADATA_QUERY_FAILED";
        }
        return "TOOL_CALL_FAILED";
    }
}
