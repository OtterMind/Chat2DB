package ai.chat2db.plugin.oscar.util;

import ai.chat2db.plugin.oscar.constant.OscarConstants;
import ai.chat2db.plugin.oscar.identifier.OscarIdentifierProcessor;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.constant.SQLConstants;
import org.apache.commons.lang3.StringUtils;

public class OscarUtils {

    public static final ISQLIdentifierProcessor OSCAR_SQL_IDENTIFIER_PROCESSOR = OscarIdentifierProcessor.INSTANCE;

    private OscarUtils() {
    }

    public static String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        return OSCAR_SQL_IDENTIFIER_PROCESSOR.quoteIdentifier(identifier);
    }

    public static String quoteIdentifierIgnoreCase(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        return OSCAR_SQL_IDENTIFIER_PROCESSOR.quoteIdentifierIgnoreCase(identifier);
    }

    public static String qualifiedName(String schemaName, String objectName) {
        if (StringUtils.isBlank(schemaName)) {
            return quoteIdentifierIgnoreCase(objectName);
        }
        return quoteIdentifierIgnoreCase(schemaName) + SQLConstants.DOT + quoteIdentifierIgnoreCase(objectName);
    }

    public static String normalizeSchema(String schemaName) {
        if (StringUtils.isBlank(schemaName)) {
            return OscarConstants.DEFAULT_SCHEMA;
        }
        return normalizeIdentifier(schemaName);
    }

    public static String normalizeIdentifier(String identifier) {
        String unquoted = OSCAR_SQL_IDENTIFIER_PROCESSOR.removeIdentifierQuote(identifier);
        return OSCAR_SQL_IDENTIFIER_PROCESSOR.convertIdentifierCase(unquoted);
    }
}
