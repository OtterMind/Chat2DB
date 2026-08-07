package ai.chat2db.community.domain.api.service.db;

import java.util.List;
import java.util.Map;

/**
 * MySQL variables and status inspection with guarded editing (MYSQL-OPS-004).
 * Status views are read-only; only variables registered in the editable registry may
 * produce a SET statement.
 */
public interface IDbVariableService {

    /**
     * Lists variables or status counters for a scope.
     *
     * @param scope GLOBAL or SESSION.
     * @param kind  VARIABLES or STATUS.
     * @return name/value pairs; STATUS views never mutate server state.
     */
    List<Map<String, Object>> variables(String scope, String kind);

    /**
     * Whether the variable is editable and, if so, its risk level. Unknown, read-only,
     * restart-only, or version-unsupported variables return null.
     *
     * @param variableName the variable name.
     * @return edit metadata, or null when the variable must stay read-only.
     */
    EditMeta editable(String variableName);

    /**
     * Generates the SET statement for an editable variable. Validates the name against the
     * registry and the value against the declared type; no statement is generated for
     * unknown variables.
     *
     * @param variableName the registered variable name.
     * @param value        the new value.
     * @param scope        SESSION, GLOBAL, PERSIST, or PERSIST_ONLY (MySQL 8.0 only).
     * @return the SET SQL preview.
     */
    String previewSetVariableSql(String variableName, String value, String scope);

    /**
     * Edit metadata for a registered variable.
     */
    record EditMeta(String name, String type, String scope, boolean highRisk) {
    }
}
