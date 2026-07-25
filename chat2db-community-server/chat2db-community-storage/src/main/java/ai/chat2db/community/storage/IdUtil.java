package ai.chat2db.community.storage;

import java.util.concurrent.atomic.AtomicLong;

import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;

public class IdUtil {

    /**
     * Last timestamp handed out. Calls within the same millisecond (or after a
     * clock rollback) borrow the next millisecond so the timestamp part is
     * strictly increasing and ids never collide.
     */
    private static final AtomicLong LAST_TIMESTAMP = new AtomicLong(-1L);

    public static Long generateId() {
        Long userId = null;
        Context context = ContextUtils.queryContext();
        if (context != null && context.getLoginUser() != null) {
            userId = context.getLoginUser().getId();
        }
        if (userId == null) {
            userId = 0L;
        }
        long timestamp = LAST_TIMESTAMP.updateAndGet(
            last -> Math.max(last + 1, System.currentTimeMillis()));
        // Kept as timestamp + userId%1000 so ids stay within JS Number.MAX_SAFE_INTEGER.
        String id = timestamp + "" + Math.floorMod(userId, 1000);
        return Long.parseLong(id);
    }

}
