package ai.chat2db.plugin.h2.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;
import org.h2.util.ParserUtil;

/**
 * H2 dialect identifier processor: double-quoted identifiers with embedded-quote
 * doubling, and single-quote doubling for string literals. Shared stateless
 * instance available via {@link #INSTANCE} for call sites without MetaData access.
 */
public class H2IdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final H2IdentifierProcessor INSTANCE = new H2IdentifierProcessor();

    /**
     * SPI-facing conditional quoting: null/blank pass through unchanged; identifiers that
     * H2 can use unquoted without case folding stay plain; anything else is safely quoted.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (ParserUtil.isSimpleIdentifier(identifier, true, false)) {
            return identifier;
        }
        return quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
        return quoteIdentifier(identifier);
    }

    /**
     * Unconditional quoting for DDL-generation call sites.
     */
    @Override
    public String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + StringUtils.replace(identifier, "\"", "\"\"") + "\"";
    }

    /**
     * Escapes a value interpolated into a single-quoted SQL string literal by
     * doubling every single quote.
     */
    @Override
    public String escapeString(String str) {
        return str == null ? null : StringUtils.replace(str, "'", "''");
    }
}
