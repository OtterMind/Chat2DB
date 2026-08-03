package ai.chat2db.community.start.ai.subscription.lifecycle;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * HTTPS browser targets allowed for ChatGPT login open-browser resolution.
 */
public final class BrowserTargetAllowlist {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "chatgpt.com",
            "auth.openai.com"
    );

    private BrowserTargetAllowlist() {
    }

    public static boolean isAllowed(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            return ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }
}
