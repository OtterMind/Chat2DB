package ai.chat2db.community.jcef.frame;

import ai.chat2db.community.tools.runtime.ProductRuntimeIdentityProvider;

final class DesktopProductTitle {

    private DesktopProductTitle() {
    }

    static String resolve() {
        return ProductRuntimeIdentityProvider.current().displayName();
    }
}
