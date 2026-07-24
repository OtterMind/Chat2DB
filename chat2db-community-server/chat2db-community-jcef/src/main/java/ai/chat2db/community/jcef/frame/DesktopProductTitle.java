package ai.chat2db.community.jcef.frame;

final class DesktopProductTitle {

    private DesktopProductTitle() {
    }

    static String resolve(boolean windows, boolean community, boolean localEdition) {
        if (windows && community) {
            return "Chat2DB Community";
        }
        if (localEdition) {
            return "Chat2DB Local";
        }
        return "Chat2DB Pro";
    }
}
