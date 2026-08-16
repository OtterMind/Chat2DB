package ai.chat2db.community.domain.api.enums;

import lombok.Getter;


@Getter
public enum ExportFileSuffixEnum {
    WORD(".docx"),
    EXCEL(".xlsx"),
    MARKDOWN(".md"),
    HTML(".html"),

    CSV(".csv"),

    XLSX(".xlsx"),

    XLS(".xls"),

    JSON(".json"),
    SQL(".sql"),
    PDF(".pdf");


    private String suffix;

    ExportFileSuffixEnum(String suffix) {
        this.suffix = suffix;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }
}
