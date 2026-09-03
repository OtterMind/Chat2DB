package ai.chat2db.community.domain.api.model.db.diagnostics;

import lombok.Data;

@Data
public class InnodbStatusSection {

    private String title;

    private String normalizedTitle;

    private boolean recognized;

    private int startLine;

    private int endLine;

    private String text;
}
