package ai.chat2db.community.domain.api.model.async;

import ai.chat2db.community.domain.api.service.task.ITaskAsyncCall;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.PrintWriter;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class AsyncContext implements ISqlExecutionStatementListener {

    private static final int TERMINAL_UPDATE_MAX_ATTEMPTS = 3;

    private enum State {
        RUNNING,
        FINISHED,
        STOP
    }

    private File writeFile;

    protected PrintWriter writer;

    protected boolean containsData;

    protected ITaskAsyncCall call;

    protected volatile boolean finish;

    protected volatile Integer progress;

    private volatile State state = State.RUNNING;

    private final Object callbackLock = new Object();

    private final Object messageLock = new Object();

    private boolean terminalUpdatePublished;

    private final AtomicReference<Statement> currentStatement = new AtomicReference<>();

    private volatile StringBuffer info = new StringBuffer();

    private volatile StringBuffer error = new StringBuffer();

    public AsyncContext(ITaskAsyncCall call, Context context, File writeFile, boolean containsData) {
        this.call = call;
        this.writeFile = writeFile;
        this.progress = 5;
        this.containsData = containsData;
        createWriter();
        appendInfo(DateUtil.formatDateTime(new Date()) + ":start------");
        asyncCallBack(context);
    }

    public File getWriteFile() {
        return writeFile;
    }

    public boolean isContainsData() {
        return containsData;
    }

    public void setProgress(Integer progress) {
        checkCancelled();
        if (progress == null) {
            return;
        }
        if (progress >= 100) {
            progress = 99;
        }
        this.progress = progress;
    }

    public void info(String message) {
        checkCancelled();
        appendInfo(message);
    }

    public void error(String message) {
        if (isStopped()) {
            return;
        }
        synchronized (messageLock) {
            error.append(message).append("\n");
            info.append(message).append("\n");
        }
    }

    public boolean stop() {
        synchronized (this) {
            if (state == State.STOP || terminalUpdatePublished) {
                return false;
            }
            state = State.STOP;
            finish = true;
        }
        cancelStatement(currentStatement.get());
        return true;
    }

    public boolean isStopped() {
        return state == State.STOP;
    }

    public void checkCancelled() {
        if (isStopped() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Task was cancelled");
        }
    }

    public void finish() {
        synchronized (this) {
            finish = true;
            if (state == State.RUNNING) {
                state = State.FINISHED;
                this.progress = 100;
                String message = DateUtil.formatDateTime(new Date()) + " " + "finish. ";
                if (writeFile != null) {
                    message += "File path:" + writeFile.getAbsolutePath();
                }
                appendInfo(message);
            }
            closeWriter();
        }
        publishTerminalUpdate();
    }

    private void closeWriter() {
        if (writer != null) {
            writer.flush();
            writer.close();
        }
    }

    public void write(String message) {
        checkCancelled();
        if (writer != null) {
            writer.write(message + "\n");
        }
    }

    private void appendInfo(String message) {
        synchronized (messageLock) {
            info.append(message).append("\n");
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
                        try {
                            callUpdate();
                        } catch (RuntimeException e) {
                            log.warn("AsyncContext polling callback failed; it will be retried", e);
                        }
                        if (finish) {
                            break;
                        }
                        try {
                            Thread.sleep(2000L * n);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (n < 5) {
                            n++;
                        }
                    }
                } finally {
                    ContextUtils.removeContext();
                }
            }).start();
        }
    }

    private void publishTerminalUpdate() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= TERMINAL_UPDATE_MAX_ATTEMPTS; attempt++) {
            try {
                callUpdate();
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("AsyncContext terminal callback failed, attempt {}/{}",
                        attempt, TERMINAL_UPDATE_MAX_ATTEMPTS, e);
            }
        }
        log.error("AsyncContext terminal callback failed after {} attempts",
                TERMINAL_UPDATE_MAX_ATTEMPTS, lastFailure);
    }

    private void callUpdate() {
        if (call == null) {
            return;
        }
        synchronized (callbackLock) {
            while (true) {
                State updateState;
                Integer updateProgress;
                String infoMessage;
                String errorMessage;
                synchronized (this) {
                    updateState = state;
                    if (updateState != State.RUNNING && terminalUpdatePublished) {
                        return;
                    }
                    updateProgress = progress;
                    synchronized (messageLock) {
                        infoMessage = info.toString();
                        errorMessage = error.toString();
                        info = new StringBuffer();
                        error = new StringBuffer();
                    }
                }

                Map<String, Object> map = new HashMap<>();
                map.put("progress", updateProgress);
                if (!infoMessage.isEmpty()) {
                    map.put("info", infoMessage);
                }
                if (!errorMessage.isEmpty()) {
                    map.put("error", errorMessage);
                }
                map.put("status", updateState.name());
                if (updateState == State.FINISHED && updateProgress == 100 && writeFile != null) {
                    map.put("downloadUrl", writeFile.getAbsolutePath());
                } else if (updateState == State.STOP) {
                    map.put("downloadUrl", "");
                }
                try {
                    call.update(map);
                } catch (RuntimeException e) {
                    restoreMessages(infoMessage, errorMessage);
                    throw e;
                }

                synchronized (this) {
                    if (state == updateState) {
                        if (updateState != State.RUNNING) {
                            terminalUpdatePublished = true;
                        }
                        return;
                    }
                }
            }
        }
    }

    private void restoreMessages(String infoMessage, String errorMessage) {
        synchronized (messageLock) {
            if (!infoMessage.isEmpty()) {
                info.insert(0, infoMessage);
            }
            if (!errorMessage.isEmpty()) {
                error.insert(0, errorMessage);
            }
        }
    }

    @Override
    public void onStatementCreated(Statement statement) {
        currentStatement.set(statement);
        if (isStopped()) {
            cancelStatement(statement);
        }
    }

    @Override
    public void onStatementClosed(Statement statement) {
        currentStatement.compareAndSet(statement, null);
    }

    private void cancelStatement(Statement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (Exception e) {
            log.warn("Failed to cancel task JDBC statement", e);
        }
    }
}
