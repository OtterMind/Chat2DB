package ai.chat2db.community.domain.api.enums.plugin;

public enum ForeignKeyActionEnum {
    CASCADE(0),
    RESTRICT(1),
    SET_NULL(2),
    NO_ACTION(3);

    private final short jdbcCode;

    ForeignKeyActionEnum(int jdbcCode) {
        this.jdbcCode = (short) jdbcCode;
    }

    public static ForeignKeyActionEnum fromJdbcCode(Short code) {
        if (code == null) {
            return null;
        }
        for (ForeignKeyActionEnum action : values()) {
            if (action.jdbcCode == code) {
                return action;
            }
        }
        return null;
    }

    public String sqlKeyword() {
        return switch (this) {
            case CASCADE -> "CASCADE";
            case RESTRICT -> "RESTRICT";
            case SET_NULL -> "SET NULL";
            case NO_ACTION -> "NO ACTION";
        };
    }
}
