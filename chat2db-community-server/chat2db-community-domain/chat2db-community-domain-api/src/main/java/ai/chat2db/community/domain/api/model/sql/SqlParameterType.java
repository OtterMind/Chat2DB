package ai.chat2db.community.domain.api.model.sql;

import com.alibaba.fastjson2.annotation.JSONCreator;

/**
 * The kind of value a user typed for one SQL parameter. Each kind maps to exactly
 * one JDBC bind call; the target column type is still decided by the database
 * from the expression context.
 * <p>
 * JSON carries the constant name only. An unknown or missing kind is a parameter
 * validation error and never falls back to {@link #STRING}.
 */
public enum SqlParameterType {

    /**
     * Any text, including the empty string; bound with {@code setString}.
     */
    STRING,

    /**
     * Decimal number text; parsed to {@code BigDecimal} and bound with {@code setBigDecimal}.
     */
    NUMBER,

    /**
     * Exactly {@code true} or {@code false}; bound with {@code setBoolean}.
     */
    BOOLEAN,

    /**
     * SQL {@code NULL}; the value must be JSON {@code null}. Bound with {@code setNull}.
     */
    NULL;

    /**
     * Reads a type by its exact constant name. Desktop requests are parsed by
     * fastjson2, which would otherwise accept other spellings such as
     * {@code number}; an unknown name yields {@code null}, which parameter
     * validation rejects. Jackson already matches names exactly.
     */
    @JSONCreator
    public static SqlParameterType fromName(String name) {
        for (SqlParameterType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
