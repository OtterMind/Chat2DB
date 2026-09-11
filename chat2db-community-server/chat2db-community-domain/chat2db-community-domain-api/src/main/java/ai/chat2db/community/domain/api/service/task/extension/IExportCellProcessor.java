package ai.chat2db.community.domain.api.service.task.extension;

import ai.chat2db.community.domain.api.model.task.extension.ExportCell;
import ai.chat2db.community.domain.api.model.task.extension.ExportCellContext;

public interface IExportCellProcessor {

    ExportCell process(ExportCellContext context, ExportCell cell);
}
