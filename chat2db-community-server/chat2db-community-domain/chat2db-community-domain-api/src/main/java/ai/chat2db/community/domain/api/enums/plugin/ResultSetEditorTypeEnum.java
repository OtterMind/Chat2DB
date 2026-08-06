package ai.chat2db.community.domain.api.enums.plugin;

import ai.chat2db.community.tools.enums.IBaseEnum;
import lombok.Getter;

import java.util.Locale;

@Getter
public enum ResultSetEditorTypeEnum implements IBaseEnum<String> {
    TEXT,
    DATE,
    TIME,
    DATETIME,
    TIMESTAMP,
    SELECT,
    MULTI_SELECT,
    ;

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getDescription() {
        return name();
    }

    public static ResultSetEditorTypeEnum from(String value) {
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return TEXT;
        }
    }
}
