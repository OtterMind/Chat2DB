package ai.chat2db.community.start.ai.subscription.lifecycle;

/**
 * Internal open-browser resolution result. The URL is only for the JCEF open-browser handler
 * and must not be serialized into generic request/error surfaces.
 */
public final class BrowserTargetResolution {

    private final boolean allowed;
    private final String httpsUrl;
    private final LifecycleErrorCode errorCode;

    private BrowserTargetResolution(boolean allowed, String httpsUrl, LifecycleErrorCode errorCode) {
        this.allowed = allowed;
        this.httpsUrl = httpsUrl;
        this.errorCode = errorCode;
    }

    public static BrowserTargetResolution allowed(String httpsUrl) {
        return new BrowserTargetResolution(true, httpsUrl, null);
    }

    public static BrowserTargetResolution denied(LifecycleErrorCode errorCode) {
        return new BrowserTargetResolution(false, null, errorCode);
    }

    public boolean allowed() {
        return allowed;
    }

    public String httpsUrl() {
        return httpsUrl;
    }

    public LifecycleErrorCode errorCode() {
        return errorCode;
    }
}
