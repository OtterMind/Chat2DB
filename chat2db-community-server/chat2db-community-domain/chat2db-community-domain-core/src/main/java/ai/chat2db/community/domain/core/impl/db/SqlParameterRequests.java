package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.SqlParameterParser;
import ai.chat2db.spi.util.SqlParameterSyntax;

import java.util.List;
import java.util.Map;

/**
 * Prepares the SQL parameters of an execute or COUNT request before the
 * execution policy, statement splitting, paging and COUNT rewriting see the SQL.
 */
final class SqlParameterRequests {

    private SqlParameterRequests() {
    }

    /**
     * Validates the supplied values against the SQL and renames positional
     * {@code ?} placeholders to synthetic names.
     * <p>
     * Supplied values are always bound, whatever the editor switch says, so an
     * open result keeps its values on paging and COUNT. When no values are supplied
     * and the switch is on, SQL that contains placeholders is rejected before
     * anything runs.
     *
     * @return the SQL with named placeholders only, or {@code null} when the SQL
     * runs without parameters.
     */
    static SqlParameterParser.NormalizedSql normalize(String sql, Boolean sqlParameters,
                                                      Map<String, SqlParameterValue> parameters,
                                                      List<SqlParameterValue> positionalParameters) {
        boolean hasValues = (parameters != null && !parameters.isEmpty())
                || (positionalParameters != null && !positionalParameters.isEmpty());
        if (!hasValues && !Boolean.TRUE.equals(sqlParameters)) {
            return null;
        }
        SqlParameterSyntax syntax = SqlParameterSyntax.forDatabaseType(currentDatabaseType());
        if (!hasValues) {
            if (syntax != null && SqlParameterParser.hasPlaceholders(sql, syntax)) {
                throw new BusinessException("sqlParameter.required");
            }
            return null;
        }
        if (syntax == null) {
            throw new BusinessException("sqlParameter.unsupportedDatabase");
        }
        return SqlParameterParser.normalize(sql, parameters, positionalParameters, syntax);
    }

    /**
     * Hands normalised parameters to the executor command.
     */
    static void apply(SqlExecuteRequest command, SqlParameterParser.NormalizedSql normalizedSql) {
        if (normalizedSql == null) {
            return;
        }
        command.setParameters(normalizedSql.parameters());
        command.setPositionalParameterStyle(normalizedSql.positional());
    }

    private static String currentDatabaseType() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return connectInfo == null ? null : connectInfo.getDbType();
    }
}
