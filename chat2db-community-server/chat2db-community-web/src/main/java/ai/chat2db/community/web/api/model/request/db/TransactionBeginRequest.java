package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.runtime.TransactionIsolationLevel;
import ai.chat2db.community.web.api.model.request.data.source.ConsoleCloseRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TransactionBeginRequest extends ConsoleCloseRequest {

    private TransactionIsolationLevel isolationLevel;
}
