package ai.chat2db.community.domain.api.model.sql;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A typed value supplied for one SQL parameter placeholder.
 * <p>
 * Named placeholders ({@code :name}) receive their values in a map keyed by
 * name; positional placeholders ({@code ?}) receive them in a list in
 * appearance order. {@link SqlParameterType#NULL} binds SQL {@code NULL}; an
 * empty {@link SqlParameterType#STRING} binds the empty string. Values are always
 * bound through the JDBC driver and are never written into the SQL text.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SqlParameterValue {

    private SqlParameterType type;

    private String value;

    public static SqlParameterValue string(String value) {
        return new SqlParameterValue(SqlParameterType.STRING, value);
    }

    public static SqlParameterValue number(String value) {
        return new SqlParameterValue(SqlParameterType.NUMBER, value);
    }

    public static SqlParameterValue bool(String value) {
        return new SqlParameterValue(SqlParameterType.BOOLEAN, value);
    }

    public static SqlParameterValue nullValue() {
        return new SqlParameterValue(SqlParameterType.NULL, null);
    }

    @Override
    public String toString() {
        // Parameter values stay out of logs and error messages.
        return "SqlParameterValue[type=" + type + "]";
    }
}
