package ai.chat2db.community.domain.api.enums;

import lombok.Getter;


@Getter
public enum ExportFileSuffixEnum {
    EXCEL(".xlsx"),

    MARKDOWN(".md"),

    CSV(".csv"),

    XLSX(".xlsx"),

    XLS(".xls"),

    JSON(".json"),
    SQL(".sql");

    private final String suffix;

    ExportFileSuffixEnum(String suffix) {
        this.suffix = suffix;
    }
}
