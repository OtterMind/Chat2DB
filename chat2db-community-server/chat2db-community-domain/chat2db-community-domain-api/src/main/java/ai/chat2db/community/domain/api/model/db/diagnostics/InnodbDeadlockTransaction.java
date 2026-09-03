package ai.chat2db.community.domain.api.model.db.diagnostics;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InnodbDeadlockTransaction {

    private String marker;

    private String transactionId;

    private Integer activeSeconds;

    private String mysqlThreadId;

    private String queryId;

    private String sql;

    private boolean victim;

    private List<String> heldLocks = new ArrayList<>();

    private List<String> waitedLocks = new ArrayList<>();
}
