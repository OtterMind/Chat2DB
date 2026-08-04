package ai.chat2db.community.domain.core.impl.operation;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationLogPageQueryRequest;
import ai.chat2db.community.domain.api.service.ops.IOpsOperationLogQueryService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class OpsOperationLogQueryServiceImpl implements IOpsOperationLogQueryService {

    private static final int DDL_PREVIEW_LENGTH = 200;

    private final IWorkspaceStorageFacade workspaceStorageFacade;

    public OpsOperationLogQueryServiceImpl(IWorkspaceStorageFacade workspaceStorageFacade) {
        this.workspaceStorageFacade = workspaceStorageFacade;
    }

    @Override
    public PageResponse<OperationLog> operationLogList(OpsOperationLogPageQueryRequest request) {
        return workspaceStorageFacade.operationLogList(request);
    }

    @Override
    public PageResponse<OperationLog> operationLogPreviewList(OpsOperationLogPageQueryRequest request) {
        PageResponse<OperationLog> page = operationLogList(request);
        if (CollectionUtils.isNotEmpty(page.getData())) {
            page.setData(page.getData().stream().map(this::preparePreview).collect(Collectors.toList()));
        }
        return page;
    }

    @Override
    public OperationLog getOperationLog(Long id) {
        return workspaceStorageFacade.getOperationLog(id);
    }

    @Override
    public Long createOperationLog(OperationLog request) {
        return workspaceStorageFacade.createOperationLog(request);
    }

    private OperationLog preparePreview(OperationLog operationLog) {
        if (operationLog == null) {
            return null;
        }

        OperationLog preview = new OperationLog();
        BeanUtils.copyProperties(operationLog, preview);
        if (StringUtils.isNotBlank(preview.getDdl()) && preview.getDdl().length() > DDL_PREVIEW_LENGTH) {
            preview.setDdl(preview.getDdl().substring(0, DDL_PREVIEW_LENGTH) + "...");
            preview.setMore(Boolean.TRUE);
        }
        return preview;
    }
}
