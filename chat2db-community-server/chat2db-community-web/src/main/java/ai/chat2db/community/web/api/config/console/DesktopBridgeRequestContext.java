package ai.chat2db.community.web.api.config.console;

import ai.chat2db.community.tools.exception.BusinessException;

import java.util.function.Supplier;

public final class DesktopBridgeRequestContext {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private DesktopBridgeRequestContext() {
    }

    public static <T> T invoke(Supplier<T> operation) {
        boolean nested = isActive();
        ACTIVE.set(true);
        try {
            return operation.get();
        } finally {
            if (!nested) {
                ACTIVE.remove();
            }
        }
    }

    public static void requireActive() {
        if (!isActive()) {
            throw new BusinessException("common.permissionDenied");
        }
    }

    static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }
}
