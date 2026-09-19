package ai.chat2db.community.jcef.update.v2;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.enums.UpdatedStatus;
import ai.chat2db.community.jcef.utils.CallJsFunctionUtil;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;

import java.util.Map;
import java.util.function.LongSupplier;

final class JcefUpdateProgressReporter {

    /** The transport reports every block, so pushes are coalesced like the previous updater. */
    static final long MIN_PUSH_INTERVAL_MILLIS = 500L;

    private final LongSupplier clock;
    private int lastPercent = -1;
    private long lastPushTimeMillis;

    JcefUpdateProgressReporter() {
        this(System::currentTimeMillis);
    }

    JcefUpdateProgressReporter(LongSupplier clock) {
        this.clock = clock;
    }

    void reset() {
        lastPercent = -1;
        lastPushTimeMillis = 0L;
    }

    /**
     * Reports download progress. Returns whether a push happened, which happens at most once per
     * {@link #MIN_PUSH_INTERVAL_MILLIS} and only while the percentage keeps advancing.
     */
    boolean progress(ConsoleResult consoleResult, long downloaded, long total) {
        int percent = total <= 0 ? 0 : (int) Math.min(99L, downloaded * 100L / total);
        long now = clock.getAsLong();
        if (percent <= lastPercent) {
            return false;
        }
        if (lastPercent >= 0 && now - lastPushTimeMillis < MIN_PUSH_INTERVAL_MILLIS) {
            return false;
        }
        lastPercent = percent;
        lastPushTimeMillis = now;
        push(consoleResult, percent, UpdatedStatus.Updating);
        return true;
    }

    void completed(ConsoleResult consoleResult) {
        push(consoleResult, 100, UpdatedStatus.Updated);
    }

    void failed(ConsoleResult consoleResult) {
        push(consoleResult, 0, UpdatedStatus.UpdateFailed);
    }

    private void push(ConsoleResult consoleResult, int percent, UpdatedStatus status) {
        consoleResult.setMessage(Map.of("progress", percent, "status", status.getName()));
        consoleResult.setActionType(ActionTypeEnum.UPDATE_PROGRESS.getName());
        if (JcefContext.getInstance().getBrowser_() != null) {
            CallJsFunctionUtil.callHandleJavaMessage(
                JcefContext.getInstance().getBrowser_(),
                JSON.toJSONString(consoleResult)
            );
        }
    }
}
