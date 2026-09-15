package ai.chat2db.community.domain.api.model.db.diagnostics;

import lombok.Data;

@Data
public class InnodbParserMessage {

    private String severity;

    private String code;

    private String message;

    private String sectionTitle;

    private Integer line;
}
