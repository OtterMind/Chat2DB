package ai.chat2db.plugin.postgresql.value.template;

import ai.chat2db.plugin.postgresql.PostgreSqlGuards;
import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;

import static ai.chat2db.plugin.postgresql.constant.PostgreSQLDmlValueTemplateConstants.*;



public class PostgreSQLDmlValueTemplate {



    public static String wrapBit(String value) {
        return String.format(BIT_TEMPLATE, PostgreSqlGuards.requireBitLiteral(value));
    }
    public static String wrapBytea(String value) {
        return String.format(BYTEA_VALUE, PostgreSqlGuards.requireHexLiteral(value));
    }

    public static String wrapJsonb(String value) {
        return String.format(JSONB_TEMPLATE, PostgreSQLIdentifierProcessor.INSTANCE.escapeString(value));
    }

    public static String wrapJson(String value) {
        return String.format(JSON_TEMPLATE, PostgreSQLIdentifierProcessor.INSTANCE.escapeString(value));
    }
}
