package ai.chat2db.spi;

import ai.chat2db.community.domain.api.service.db.IDbVariableService.EditMeta;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Provides dialect-specific database variable and status operations.
 */
public interface IVariableManager {

    List<Map<String, Object>> variables(Connection connection, String dbVersion, String scope, String kind);

    EditMeta editable(Connection connection, String dbVersion, String variableName);

    String previewSetVariableSql(Connection connection, String dbVersion, String variableName, String value,
                                 String scope);
}
