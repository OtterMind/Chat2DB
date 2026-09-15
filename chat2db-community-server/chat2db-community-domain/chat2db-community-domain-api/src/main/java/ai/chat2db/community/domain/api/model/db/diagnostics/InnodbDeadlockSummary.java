package ai.chat2db.community.domain.api.model.db.diagnostics;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InnodbDeadlockSummary {

    private boolean found;

    private String message;

    private String time;

    private String victimTransaction;

    private List<InnodbDeadlockTransaction> transactions = new ArrayList<>();

    private String rawText;
}
