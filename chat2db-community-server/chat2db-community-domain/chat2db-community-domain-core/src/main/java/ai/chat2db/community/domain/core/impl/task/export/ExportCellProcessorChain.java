package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.extension.ExportCell;
import ai.chat2db.community.domain.api.model.task.extension.ExportCellContext;
import ai.chat2db.community.domain.api.service.task.extension.IExportCellProcessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class ExportCellProcessorChain {

    private final List<IExportCellProcessor> processors;

    public ExportCellProcessorChain(List<IExportCellProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

    public ExportCell process(ExportCellContext context, ExportCell cell) {
        ExportCell current = cell;
        for (IExportCellProcessor processor : processors) {
            current = Objects.requireNonNull(processor.process(context, current),
                    () -> processor.getClass().getName() + " returned a null export cell");
        }
        return current;
    }

    public boolean isEmpty() {
        return processors.isEmpty();
    }
}
