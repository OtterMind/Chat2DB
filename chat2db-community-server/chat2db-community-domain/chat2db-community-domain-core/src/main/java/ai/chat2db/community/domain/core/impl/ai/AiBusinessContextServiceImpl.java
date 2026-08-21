package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.ai.AiBusinessContextResult;
import ai.chat2db.community.domain.api.model.request.ai.AiBusinessContextBuildRequest;
import ai.chat2db.community.domain.api.service.ai.IAiBusinessContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AiBusinessContextServiceImpl implements IAiBusinessContextService {

    @Override
    public AiBusinessContextResult resolve(AiBusinessContextBuildRequest request) {
        return AiBusinessContextResult.empty();
    }
}
