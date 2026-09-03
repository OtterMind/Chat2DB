package ai.chat2db.plugin.mysql.variable;

import ai.chat2db.community.domain.api.service.db.IDbVariableService.EditMeta;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.plugin.mysql.MysqlVersionSupport;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IVariableManager;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.chat2db.plugin.mysql.constant.MysqlVariableConstants.*;

public class MysqlVariableManager implements IVariableManager {

    @Override
    public List<Map<String, Object>> variables(Connection connection, String dbVersion, String scope, String kind) {
        List<Map<String, Object>> rows = queryNameValueRows(connection, showSql(scope, kind));
        if (!KIND_VARIABLES.equalsIgnoreCase(kind) || !supportsVariableInfo(dbVersion)) {
            return rows;
        }
        Map<String, VariableInfo> infoByName = queryVariableInfo(connection);
        rows.forEach(row -> addVariableInfo(row, infoByName.get(normalizeName((String) row.get("name")))));
        return rows;
    }

    @Override
    public EditMeta editable(Connection connection, String dbVersion, String variableName) {
        MysqlEditableVariable variable = MysqlEditableVariable.byName(variableName);
        if (variable == null) {
            return null;
        }
        VariableInfo variableInfo = supportedVariableInfo(connection, dbVersion, variable);
        if (supportsVariableInfo(dbVersion) && variableInfo == null) {
            return null;
        }
        return new EditMeta(variable.getName(), variable.getType().name(), dynamicScopes(variable),
                persistScopes(variable, MysqlVersionSupport.supportsPersistedVariables(dbVersion)),
                variable.getRisk() == MysqlEditableVariable.Risk.HIGH,
                variableInfo == null ? null : variableInfo.source(),
                variableInfo == null ? null : variableInfo.path(),
                variableInfo == null ? null : variableInfo.minValue(),
                variableInfo == null ? null : variableInfo.maxValue());
    }

    @Override
    public String previewSetVariableSql(Connection connection, String dbVersion, String variableName, String value,
                                        String scope) {
        if (StringUtils.isAnyBlank(variableName, value, scope)) {
            throw new BusinessException(ERROR_REQUIRED);
        }
        MysqlEditableVariable variable = MysqlEditableVariable.byName(variableName);
        if (variable == null || supportsVariableInfo(dbVersion)
                && supportedVariableInfo(connection, dbVersion, variable) == null) {
            throw new BusinessException(ERROR_READ_ONLY);
        }
        validateValue(variable, value);
        String normalizedScope = scope.trim().toUpperCase(Locale.ROOT);
        validateScope(variable, normalizedScope, dbVersion);
        String template = switch (normalizedScope) {
            case SCOPE_SESSION -> SQL_SET_SESSION;
            case SCOPE_GLOBAL -> SQL_SET_GLOBAL;
            case SCOPE_PERSIST -> SQL_SET_PERSIST;
            case SCOPE_PERSIST_ONLY -> SQL_SET_PERSIST_ONLY;
            default -> throw new BusinessException(ERROR_UNSUPPORTED_SCOPE);
        };
        return String.format(template, variable.getName(), literalValue(variable, value));
    }

    private static String showSql(String scope, String kind) {
        boolean global = SCOPE_GLOBAL.equalsIgnoreCase(scope);
        boolean variables = KIND_VARIABLES.equalsIgnoreCase(kind);
        if (global) {
            return variables ? SQL_SHOW_GLOBAL_VARIABLES : SQL_SHOW_GLOBAL_STATUS;
        }
        return variables ? SQL_SHOW_SESSION_VARIABLES : SQL_SHOW_SESSION_STATUS;
    }

    private static void validateScope(MysqlEditableVariable variable, String scope, String dbVersion) {
        if (dynamicScopes(variable).contains(scope)
                || persistScopes(variable, MysqlVersionSupport.supportsPersistedVariables(dbVersion)).contains(scope)) {
            return;
        }
        throw new BusinessException(ERROR_UNSUPPORTED_SCOPE);
    }

    private static List<String> dynamicScopes(MysqlEditableVariable variable) {
        return switch (variable.getScope()) {
            case SESSION -> List.of(SCOPE_SESSION);
            case GLOBAL_ONLY -> List.of(SCOPE_GLOBAL);
            case BOTH -> List.of(SCOPE_SESSION, SCOPE_GLOBAL);
        };
    }

    static List<String> persistScopes(MysqlEditableVariable variable, boolean supportsPersist) {
        if (!supportsPersist || variable.getPersistCapability() == MysqlEditableVariable.PersistCapability.NONE) {
            return List.of();
        }
        return List.of(SCOPE_PERSIST, SCOPE_PERSIST_ONLY);
    }

    private static boolean supportsVariableInfo(String dbVersion) {
        return MysqlVersionSupport.supportsPersistedVariables(dbVersion);
    }

    private VariableInfo supportedVariableInfo(Connection connection, String dbVersion,
                                               MysqlEditableVariable variable) {
        if (!supportsVariableInfo(dbVersion)) {
            return null;
        }
        return queryVariableInfo(connection).get(normalizeName(variable.getName()));
    }

    protected List<Map<String, Object>> queryNameValueRows(Connection connection, String sql) {
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", resultSet.getString(1));
                row.put("value", resultSet.getString(2));
                rows.add(row);
            }
            return rows;
        });
    }

    protected Map<String, VariableInfo> queryVariableInfo(Connection connection) {
        try {
            return DefaultSQLExecutor.getInstance().execute(connection, SQL_VARIABLE_INFO, resultSet -> {
                List<String> columns = columns(resultSet.getMetaData());
                Map<String, VariableInfo> rows = new LinkedHashMap<>();
                while (resultSet.next()) {
                    VariableInfo info = readVariableInfo(resultSet, columns);
                    rows.put(normalizeName(info.name()), info);
                }
                return rows;
            });
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private static VariableInfo readVariableInfo(ResultSet resultSet, List<String> columns) throws SQLException {
        return new VariableInfo(
                resultSet.getString(COLUMN_VARIABLE_NAME),
                nullableString(resultSet, columns, COLUMN_VARIABLE_SOURCE),
                nullableString(resultSet, columns, COLUMN_VARIABLE_PATH),
                nullableString(resultSet, columns, COLUMN_MIN_VALUE),
                nullableString(resultSet, columns, COLUMN_MAX_VALUE),
                nullableString(resultSet, columns, COLUMN_SET_TIME),
                nullableString(resultSet, columns, COLUMN_SET_USER),
                nullableString(resultSet, columns, COLUMN_SET_HOST));
    }

    private static void addVariableInfo(Map<String, Object> row, VariableInfo variableInfo) {
        if (variableInfo == null) {
            return;
        }
        row.put("source", variableInfo.source());
        row.put("path", variableInfo.path());
        row.put("minValue", variableInfo.minValue());
        row.put("maxValue", variableInfo.maxValue());
        row.put("setTime", variableInfo.setTime());
        row.put("setUser", variableInfo.setUser());
        row.put("setHost", variableInfo.setHost());
    }

    private static List<String> columns(ResultSetMetaData metaData) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            columns.add(metaData.getColumnLabel(i).toUpperCase(Locale.ROOT));
        }
        return columns;
    }

    private static String nullableString(ResultSet resultSet, List<String> columns, String column)
            throws SQLException {
        return columns.contains(column) ? resultSet.getString(column) : null;
    }

    private static String normalizeName(String name) {
        return StringUtils.trimToEmpty(name).toLowerCase(Locale.ROOT);
    }

    private static void validateValue(MysqlEditableVariable variable, String value) {
        switch (variable.getType()) {
            case NUMBER -> {
                try {
                    Long.parseLong(value.trim());
                } catch (NumberFormatException exception) {
                    throw new BusinessException(ERROR_INVALID_NUMBER);
                }
            }
            case ONOFF -> {
                String upper = value.trim().toUpperCase(Locale.ROOT);
                if (!"ON".equals(upper) && !"OFF".equals(upper) && !"1".equals(upper) && !"0".equals(upper)) {
                    throw new BusinessException(ERROR_INVALID_ON_OFF);
                }
            }
            case STRING -> {
                // The database validates the concrete string value when the preview is executed.
            }
        }
    }

    private static String literalValue(MysqlEditableVariable variable, String value) {
        if (variable.getType() == MysqlEditableVariable.Type.ONOFF) {
            String upper = value.trim().toUpperCase(Locale.ROOT);
            return "1".equals(upper) || "ON".equals(upper) ? "ON" : "OFF";
        }
        if (variable.getType() == MysqlEditableVariable.Type.NUMBER) {
            return value.trim();
        }
        return "'" + value.replace("'", "''") + "'";
    }

    record VariableInfo(String name, String source, String path, String minValue, String maxValue,
                        String setTime, String setUser, String setHost) {
    }
}
