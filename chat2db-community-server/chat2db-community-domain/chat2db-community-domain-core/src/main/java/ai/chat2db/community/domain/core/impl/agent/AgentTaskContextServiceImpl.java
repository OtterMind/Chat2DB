package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskContextCreateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskContextService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class AgentTaskContextServiceImpl implements IAgentTaskContextService {

    private static final int MAX_CONTENT_LENGTH = 200_000;

    private final IAgentControlStorage storage;
    private final IAgentTaskService taskService;

    public AgentTaskContextServiceImpl(IAgentControlStorage storage, IAgentTaskService taskService) {
        this.storage = storage;
        this.taskService = taskService;
    }

    @Override
    public AgentTaskContext append(AgentTaskContextCreateRequest request) {
        validate(request);
        taskService.get(request.getTaskId());

        AgentTaskContext context = new AgentTaskContext();
        context.setId(UUID.randomUUID().toString());
        context.setTaskId(request.getTaskId());
        context.setType(request.getType());
        context.setTitle(StringUtils.trimToNull(request.getTitle()));
        context.setContent(StringUtils.trim(request.getContent()));
        context.setAttachmentName(StringUtils.trimToNull(request.getAttachmentName()));
        context.setAttachmentMimeType(StringUtils.trimToNull(request.getAttachmentMimeType()));
        context.setAttachmentSize(request.getAttachmentSize());
        context.setCreatedBy(request.getCreatedBy());
        context.setCreatedAt(new Date());
        return storage.appendTaskContext(context);
    }

    @Override
    public List<AgentTaskContext> list(String taskId) {
        taskService.get(taskId);
        return storage.listTaskContexts(taskId);
    }

    private void validate(AgentTaskContextCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("task context request is required");
        }
        if (StringUtils.isBlank(request.getTaskId())) {
            throw new IllegalArgumentException("task id is required");
        }
        if (request.getType() == null) {
            throw new IllegalArgumentException("task context type is required");
        }
        if (StringUtils.isBlank(request.getContent())) {
            throw new IllegalArgumentException("task context content is required");
        }
        if (request.getContent().length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("task context content exceeds 200000 characters");
        }
        if (request.getType() == AgentTaskContextTypeEnum.ATTACHMENT
                && StringUtils.isBlank(request.getAttachmentName())) {
            throw new IllegalArgumentException("attachment context requires a file name");
        }
        if (request.getAttachmentSize() != null && request.getAttachmentSize() < 0) {
            throw new IllegalArgumentException("attachment size cannot be negative");
        }
    }
}
