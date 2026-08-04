package ai.chat2db.plugin.mysql.value.template;

import ai.chat2db.plugin.mysql.MysqlSqlGuards;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;

import static ai.chat2db.plugin.mysql.constant.MysqlDmlValueTemplateConstants.*;


public class MysqlDmlValueTemplate {




    public static String wrapGeometry(String value) {
        return String.format(GEOMETRY_TEMPLATE, MysqlIdentifierProcessor.INSTANCE.escapeString(value));
    }

    public static String wrapBit(String value) {
        return String.format(BIT_TEMPLATE, MysqlSqlGuards.requireBitLiteral(value));
    }

    public static String wrapHex(String value) {
        return String.format(HEX_TEMPLATE, MysqlSqlGuards.requireHexDigits(value));
    }
}
