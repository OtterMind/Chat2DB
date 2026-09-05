package ai.chat2db.plugin.mysql.constant;

public final class MysqlAccountManageConstants {

    public static final String ACCOUNT_DISPLAY_NAME_SEPARATOR = "@";
    public static final String ERROR_KEY_ACCOUNT_EXECUTE_FAILED = "mysql.account.executeFailed";
    public static final String ERROR_KEY_ACCOUNT_GRANTS_UNAVAILABLE = "mysql.account.grantsUnavailable";
    public static final String ERROR_KEY_ACCOUNT_LIST_UNAVAILABLE = "mysql.account.listUnavailable";
    public static final String ERROR_KEY_ACCOUNT_PREVIEW_TOKEN_MISMATCH = "mysql.account.previewTokenMismatch";
    public static final String ERROR_KEY_ACCOUNT_SECURITY_MANAGEMENT_UNSUPPORTED = "mysql.account.securityManagementUnsupported";
    public static final String ERROR_KEY_ACCOUNT_AUTH_PLUGIN_UNAVAILABLE = "mysql.account.authPluginUnavailable";
    public static final String ERROR_KEY_ACCOUNT_AUTH_PLUGIN_UNSUPPORTED = "mysql.account.authPluginUnsupported";
    public static final String FIELD_ACCOUNT_LOCKED = "account_locked";
    public static final String FIELD_HOST = "Host";
    public static final String FIELD_PLUGIN = "plugin";
    public static final String FIELD_SSL_TYPE = "ssl_type";
    public static final String FIELD_SSL_CIPHER = "ssl_cipher";
    public static final String FIELD_X509_ISSUER = "x509_issuer";
    public static final String FIELD_X509_SUBJECT = "x509_subject";
    public static final String FIELD_USER = "User";
    public static final String MESSAGE_OK = "OK";
    public static final String SQL_SELECT_CURRENT_USER = "SELECT CURRENT_USER()";
    public static final String SQL_SELECT_ACTIVE_AUTHENTICATION_PLUGINS = "SELECT PLUGIN_NAME FROM INFORMATION_SCHEMA.PLUGINS WHERE PLUGIN_TYPE = 'AUTHENTICATION' AND PLUGIN_STATUS = 'ACTIVE' ORDER BY PLUGIN_NAME";
    public static final String SQL_SELECT_MYSQL_USERS = "SELECT User, Host, plugin, ssl_type, ssl_cipher, x509_issuer, x509_subject FROM mysql.user ORDER BY User, Host";
    public static final String SQL_SELECT_MYSQL_USERS_WITH_LOCK = "SELECT User, Host, plugin, account_locked, ssl_type, ssl_cipher, x509_issuer, x509_subject FROM mysql.user ORDER BY User, Host";
    public static final String VALUE_ACCOUNT_LOCKED_YES = "Y";
    public static final String VALUE_MYSQL_SSL_TYPE_ANY = "ANY";
    public static final String VALUE_TLS_REQUIREMENT_NONE = "NONE";
    public static final String VALUE_TLS_REQUIREMENT_SSL = "SSL";

    private MysqlAccountManageConstants() {
    }
}
