package ai.chat2db.community.jcef.frame;

final class DesktopProductTitle {

    private DesktopProductTitle() {
    }

    static String resolve(boolean community, boolean localEdition) {
        if (community) {
            return "Chat2DB Community";
        }
        if (localEdition) {
            return "Chat2DB Local";
        }
        return "Chat2DB Pro";
    }
}
