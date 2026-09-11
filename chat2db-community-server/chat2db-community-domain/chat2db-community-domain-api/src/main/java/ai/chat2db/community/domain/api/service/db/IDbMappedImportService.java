package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.db.MappedImportExecution;

public interface IDbMappedImportService {

    Long submit(MappedImportExecution execution);
}
