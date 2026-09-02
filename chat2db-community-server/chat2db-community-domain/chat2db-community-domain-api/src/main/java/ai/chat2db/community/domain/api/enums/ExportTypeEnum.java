package ai.chat2db.community.domain.api.enums;

import ai.chat2db.community.tools.enums.IBaseEnum;
import lombok.Getter;


@Getter
public enum ExportTypeEnum implements IBaseEnum<String> {


    CSV("CSV"),


    JSON("JSON"),


    NDJSON("NDJSON"),


    INSERT("INSERT"),


    EXCEL("EXCEL"),


    MARKDOWN("MARKDOWN");

    final String description;

    ExportTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }

    public static ExportTypeEnum from(String value) {
        if (value == null || value.isBlank()) {
            return INSERT;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return INSERT;
        }
    }

}
