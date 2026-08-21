package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbTriggerService;
import ai.chat2db.community.domain.core.util.ListSorter;
import ai.chat2db.community.domain.api.model.metadata.Trigger;
import ai.chat2db.spi.model.request.TriggerMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DbTriggerServiceImpl implements IDbTriggerService {
    @Override
    public List<Trigger> triggers(String databaseName, String schemaName) {
        List<Trigger> triggers = Chat2DBContext.getDbMetaData().triggers(Chat2DBContext.getConnection(), databaseName, schemaName);
        ListSorter.sortByKey(triggers, Trigger::getTriggerName);
        return triggers;
    }

    @Override
    public Trigger detail(String databaseName, String schemaName, String triggerName) {
        return Chat2DBContext.getDbMetaData().trigger(Chat2DBContext.getConnection(),
                new TriggerMetadataRequest(databaseName, schemaName, triggerName));
    }

    @Override
    public void drop(String databaseName, String schemaName, String triggerName) {
        Chat2DBContext.getDbManager().dropTrigger(Chat2DBContext.getConnection(),
                databaseName, schemaName, triggerName);
    }
}
