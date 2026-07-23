package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.SupportedDatabaseSummary;
import ai.chat2db.community.domain.api.service.db.IDbSupportedDatabaseService;
import ai.chat2db.spi.config.SupportedDatabaseRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Serves the supported-database inventory from the SPI plugin registry.
 */
@Service
public class DbSupportedDatabaseServiceImpl implements IDbSupportedDatabaseService {

    @Override
    public List<SupportedDatabaseSummary> listSupportedDatabases() {
        return SupportedDatabaseRegistry.listSupportedDatabases();
    }
}
