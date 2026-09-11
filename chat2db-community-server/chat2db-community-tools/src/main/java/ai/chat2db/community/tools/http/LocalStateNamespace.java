package ai.chat2db.community.tools.http;

import ai.chat2db.community.tools.runtime.ProductRuntimeIdentityProvider;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

public final class LocalStateNamespace {

    public static final String PROPERTY_NAME = "chat2db.local-state.namespace";

    private static final Pattern VALID_NAMESPACE = Pattern.compile("[A-Za-z0-9_-]+");

    private LocalStateNamespace() {
    }

    public static String current() {
        String namespace = StringUtils.defaultIfBlank(
                System.getProperty(PROPERTY_NAME),
                ProductRuntimeIdentityProvider.current().localStateNamespace()
        );
        if (!VALID_NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid local state namespace: " + namespace);
        }
        return namespace;
    }

    static String fileName(String stateType, String profile) {
        return "chat2db-" + current() + "-" + stateType + "-" + profile;
    }
}
