package ai.chat2db.plugin.mysql.constant;

public final class MysqlDatabasePropertiesConstants {

    public static final String SQL_DATABASE_INFO =
            "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME "
                    + "FROM information_schema.schemata WHERE SCHEMA_NAME = ?";
    public static final String FIELD_DEFAULT_CHARACTER_SET_NAME = "DEFAULT_CHARACTER_SET_NAME";
    public static final String FIELD_DEFAULT_COLLATION_NAME = "DEFAULT_COLLATION_NAME";

    private MysqlDatabasePropertiesConstants() {
    }
}
