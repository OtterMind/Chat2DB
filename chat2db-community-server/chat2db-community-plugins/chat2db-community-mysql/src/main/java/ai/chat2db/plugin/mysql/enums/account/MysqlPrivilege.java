package ai.chat2db.plugin.mysql.enums.account;

import ai.chat2db.community.tools.exception.BusinessException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum MysqlPrivilege {
    SELECT("SELECT"),
    INSERT("INSERT"),
    UPDATE("UPDATE"),
    DELETE("DELETE"),
    CREATE("CREATE"),
    DROP("DROP"),
    ALTER("ALTER"),
    INDEX("INDEX"),
    REFERENCES("REFERENCES"),
    EXECUTE("EXECUTE"),
    ALTER_ROUTINE("ALTER ROUTINE"),
    SHOW_VIEW("SHOW VIEW"),
    TRIGGER("TRIGGER"),
    EVENT("EVENT"),
    CREATE_TEMPORARY_TABLES("CREATE TEMPORARY TABLES");

    private final String sqlName;

    MysqlPrivilege(String sqlName) {
        this.sqlName = sqlName;
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    public static String sqlName(String value) {
        try {
            return MysqlPrivilege.valueOf(value.trim().toUpperCase(Locale.ROOT)).sqlName;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("mysql.account.privilegeUnsupported");
        }
    }
}
