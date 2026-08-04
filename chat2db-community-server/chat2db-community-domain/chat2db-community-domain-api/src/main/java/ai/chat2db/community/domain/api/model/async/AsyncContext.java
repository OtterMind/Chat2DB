package ai.chat2db.community.domain.api.model.async;

import ai.chat2db.community.domain.api.service.task.ITaskAsyncCall;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class AsyncContext {

    private File writeFile;

    protected PrintWriter writer;

    protected boolean containsData;

    protected ITaskAsyncCall call;

    protected volatile boolean finish;

    protected volatile Integer progress;

    private volatile StringBuffer info = new StringBuffer();

    private volatile StringBuffer error = new StringBuffer();

    public AsyncContext(ITaskAsyncCall call, Context context, File writeFile, boolean containsData) {
        this.call = call;
        this.writeFile = writeFile;
        this.progress = 5;
        this.containsData = containsData;
        createWriter();
        asyncCallBack(context);
        info(DateUtil.formatDateTime(new Date()) + ":start------");
    }

    public File getWriteFile() {
        return writeFile;
    }

    public boolean isContainsData() {
        return containsData;
    }

    public void setProgress(Integer progress) {
        if (progress == null) {
            return;
        }
        if (progress >= 100) {
            progress = 99;
        }
        this.progress = progress;
    }

    public void info(String message) {
        info.append(message).append("\n");
    }

    public void error(String message) {
        error.append(message).append("\n");
        info.append(message).append("\n");
    }

    public void stop() {
        this.finish = true;
    }

    public void finish() {
        finish = true;
        this.progress = 100;
        String message = DateUtil.formatDateTime(new Date()) + " " + "finish. ";
        if (writeFile != null) {
            message += "File path:" + writeFile.getAbsolutePath();
        }
        info(message);
        if (writer != null) {
            writer.flush();
            writer.close();
        }
        callUpdate();
    }

    public void write(String message) {
        if (writer != null) {
            writer.write(message + "\n");
        }
    }

    private void createWriter() {
        if (writeFile != null) {
            this.writer = FileUtil.getPrintWriter(writeFile, "UTF-8", false);
        }
    }

    private void asyncCallBack(Context context) {
        if (call != null && context != null) {
            new Thread(() -> {
                try {
                    ContextUtils.setContext(context);
                    int n = 1;
                    while (!finish) {
                        callUpdate();
                        Thread.sleep(2000L * n);
                        if (n < 5) {
                            n++;
                        }
                    }
                } catch (Exception e) {
                    log.error("AsyncContext call error", e);
                } finally {
                    ContextUtils.removeContext();
                }
            }).start();
        }
    }

    private void callUpdate() {
        if (call == null) {
            return;
        }
        Map<String, Object> map = new HashMap<>();
        map.put("progress", progress);
        map.put("info", info.toString());
        map.put("error", error.toString());
        map.put("status", finish ? "FINISHED" : "RUNNING");
        if (progress == 100 && writeFile != null) {
            map.put("downloadUrl", writeFile.getAbsolutePath());
        }
        info = new StringBuffer();
        error = new StringBuffer();
        call.update(map);
    }
}
