package ai.chat2db.community.tools.runtime;

import java.util.Comparator;
import java.util.ServiceLoader;

/**
 * Resolves the product identity supplied by a higher product layer.
 */
public final class ProductRuntimeIdentityProvider {

    private static final ProductRuntimeIdentity COMMUNITY = new CommunityProductRuntimeIdentity();
    private static final ProductRuntimeIdentity CURRENT = ServiceLoader.load(ProductRuntimeIdentity.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .max(Comparator.comparingInt(ProductRuntimeIdentity::priority))
            .orElse(COMMUNITY);

    private ProductRuntimeIdentityProvider() {
    }

    public static ProductRuntimeIdentity current() {
        return CURRENT;
    }

    private static final class CommunityProductRuntimeIdentity implements ProductRuntimeIdentity {

        private static final String COMMUNITY_MODE = "community";

        @Override
        public boolean communityRuntime() {
            return true;
        }

        @Override
        public boolean offlineRuntime() {
            return false;
        }

        @Override
        public String runtimeMode() {
            return System.getProperty("chat2db.runtime.mode", COMMUNITY_MODE);
        }

        @Override
        public String networkStatus() {
            return System.getProperty("chat2db.network.status", "OFFLINE");
        }

        @Override
        public String stateDirectoryName() {
            return ".chat2db-community";
        }

        @Override
        public String settingsDirectoryName() {
            return "chat2db_cache_community";
        }

        @Override
        public String runtimeConfigFileName(String environment) {
            return "runtime_config_" + environment + ".json";
        }

        @Override
        public String clientIdFileName() {
            return "client_uuid";
        }

        @Override
        public String displayName() {
            return "Chat2DB Community";
        }

        @Override
        public String protocolScheme() {
            return "chat2db-community";
        }

        @Override
        public String updateBaseUrl() {
            return "https://cdn.chat2db-ai.com/community/updates/";
        }
    }
}
