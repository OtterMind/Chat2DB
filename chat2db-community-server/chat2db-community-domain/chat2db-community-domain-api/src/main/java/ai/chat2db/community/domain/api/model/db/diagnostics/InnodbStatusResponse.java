package ai.chat2db.community.domain.api.model.db.diagnostics;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InnodbStatusResponse {

    private String rawText;

    private String capturedAt;

    private List<InnodbStatusSection> sections = new ArrayList<>();

    private InnodbDeadlockSummary latestDeadlock;

    private List<InnodbParserMessage> messages = new ArrayList<>();
}
