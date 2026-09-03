package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.metadata.Event;
import ai.chat2db.community.domain.api.service.db.IDbEventService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IEventManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DbEventServiceImpl implements IDbEventService {

    @Override
    public List<Map<String, Object>> list(String databaseName) {
        if (StringUtils.isBlank(databaseName)) {
            throw new BusinessException("database.name.required");
        }
        return eventManager().list(Chat2DBContext.getConnection(), databaseName);
    }

    @Override
    public Event detail(String databaseName, String schemaName, String eventName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(eventName)) {
            throw new BusinessException("event.name.required");
        }
        return eventManager().detail(Chat2DBContext.getConnection(), databaseName, schemaName, eventName);
    }

    @Override
    public Map<String, Object> schedulerStatus(String databaseName) {
        if (StringUtils.isBlank(databaseName)) {
            throw new BusinessException("database.name.required");
        }
        return eventManager().schedulerStatus(Chat2DBContext.getConnection(), databaseName);
    }

    @Override
    public String dropEventSql(String databaseName, String eventName) {
        requireEventName(databaseName, eventName);
        return eventManager().buildDropEvent(databaseName, eventName);
    }

    @Override
    public String setEventEnabledSql(String databaseName, String eventName, boolean enabled) {
        requireEventName(databaseName, eventName);
        return eventManager().buildAlterEventEnabled(databaseName, eventName, enabled);
    }

    private static void requireEventName(String databaseName, String eventName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(eventName)) {
            throw new BusinessException("event.name.required");
        }
    }

    private static IEventManager eventManager() {
        IEventManager manager = Chat2DBContext.getEventManager();
        if (manager == null) {
            throw new BusinessException("event.management.unsupported");
        }
        return manager;
    }
}
