package ai.chat2db.community.start.ai.subscription.lifecycle;

/**
 * ChatGPT login modes exposed to the lifecycle layer.
 * Maps to app-server types {@code chatgpt} and {@code chatgptDeviceCode}.
 */
public enum LoginType {
    BROWSER("chatgpt"),
    DEVICE("chatgptDeviceCode");

    private final String appServerType;

    LoginType(String appServerType) {
        this.appServerType = appServerType;
    }

    public String appServerType() {
        return appServerType;
    }
}
