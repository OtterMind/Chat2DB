package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.ITaskProgressListener;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import cn.hutool.core.date.DateUtil;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.util.Date;

public class ConsoleTaskProgressListener implements ITaskProgressListener {


    private final TaskExecutionContext context;
    private final long size;

    public ConsoleTaskProgressListener(TaskExecutionContext context, File file) {
        this.context = context;
        try {
            this.size = Files.size(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onProgress(long l, int i) {
        long progress = size <= 0 ? 99 : l * 100 / size;
        int p = Integer.parseInt(progress + "");
        if (p >= 100) {
            p = 99;
        }
        context.reportProgress(p, TaskStage.IMPORTING.name(),
                DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + " all bytes:" + size
                        + ",current bytes:" + l + ",progress:" + progress + "%" + " ,statement:" + i);
    }
}
