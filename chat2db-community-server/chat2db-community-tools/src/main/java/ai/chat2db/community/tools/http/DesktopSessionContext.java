package ai.chat2db.community.tools.http;

import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import java.util.function.Supplier;

/**
 * The logged-in identity a desktop shell keeps in its local cookie store.
 * <p>
 * A desktop bridge call runs on a JCEF thread that never passed through the request context, so code
 * that reads user-scoped data has to establish it first: the organization token is what decrypts a
 * cloud-stored connection. Credential values are never read here, only the organization identity.
 */
public final class DesktopSessionContext {

    /** Cookie names the gateway and the desktop shell agree on. */
    public static final String ORGANIZATION_TOKEN_COOKIE = "Chat2db-Organization-Token";

    public static final String ORGANIZATION_ID_COOKIE = "Chat2db-Organization-Id";

    private DesktopSessionContext() {
    }

    /**
     * Runs the action under the desktop identity, restoring whatever context was active before.
     * An action that already runs inside a request context keeps that context untouched.
     */
    public static <T> T call(Supplier<T> action) {
        Context previous = ContextUtils.queryContext();
        if (previous == null) {
            ContextUtils.setContext(current());
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ContextUtils.removeContext();
            } else {
                ContextUtils.setContext(previous);
            }
        }
    }

    private static Context current() {
        return Context.builder()
                .organizationToken(LocalCookie.getCookie(ORGANIZATION_TOKEN_COOKIE))
                .organizationId(parseOrganizationId(LocalCookie.getCookie(ORGANIZATION_ID_COOKIE)))
                .build();
    }

    private static Long parseOrganizationId(String value) {
        return value != null && value.matches("\\d+") ? Long.valueOf(value) : null;
    }
}
