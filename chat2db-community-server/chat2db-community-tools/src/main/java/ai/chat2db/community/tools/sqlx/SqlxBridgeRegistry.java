package ai.chat2db.community.tools.sqlx;

/**
 * Holds the desktop-side SQLX bridge the startup module assembled.
 */
public class SqlxBridgeRegistry {

    private static volatile SqlxBridge bridge;

    private SqlxBridgeRegistry() {
    }

    public static void register(SqlxBridge sqlxBridge) {
        bridge = sqlxBridge;
    }

    public static boolean isRegistered() {
        return bridge != null;
    }

    public static SqlxBridge getBridge() {
        SqlxBridge current = bridge;
        if (current == null) {
            throw new IllegalStateException("SQLX bridge is not registered");
        }
        return current;
    }
}
