package ai.chat2db.plugin.generic.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

/**
 * Generic dialect identifier processor. The SPI-facing {@link #quoteIdentifier(String)}
 * is conditional: identifiers that are already valid plain identifiers (and not
 * reserved keywords) are returned unquoted so completion/matching consumers keep
 * working; anything else is wrapped in double quotes (ANSI default) with embedded
 * quotes doubled. Call sites that historically always quoted use
 * {@link #quoteIdentifierAlways(String)} (or the SPI always-quote variant
 * {@link #quoteIdentifierIgnoreCase(String)}). The generic adapter serves mixed
 * dialects via DBConfig templates, so a dialect-parameterized
 * {@link #quoteIdentifier(String, char)} variant is also provided for call sites that
 * know the target quote char. String literals are escaped by doubling single quotes.
 * Shared stateless instance available via {@link #INSTANCE}.
 */
public class GenericIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    public static final GenericIdentifierProcessor INSTANCE = new GenericIdentifierProcessor();

    /**
     * Conditional quoting for SPI/completion paths: null/blank pass through; valid
     * plain identifiers that are not reserved keywords are returned unquoted;
     * everything else is double-quoted like {@link #quoteIdentifierAlways}.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (isValidIdentifier(identifier) && !isReservedKeyword(identifier.toUpperCase(), null, null)) {
            return identifier;
        }
        return quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
        return quoteIdentifier(identifier);
    }

    /**
     * SPI always-quote variant (preserve case, always quote).
     */
    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
        return quoteIdentifierAlways(identifier);
    }

    /**
     * Unconditional double-quote wrapping. Every quote in the raw identifier,
     * including boundary quotes, is treated as identifier content so the SPI
     * always-quote/remove-quote round-trip contract is preserved.
     */
    @Override
    public String quoteIdentifierAlways(String identifier) {
        return super.quoteIdentifierAlways(identifier);
    }

    /**
     * Escapes a value interpolated into a single-quoted SQL string literal by
     * doubling every single quote.
     */
    @Override
    public String escapeString(String str) {
        return str == null ? null : StringUtils.replace(str, "'", "''");
    }

    /**
     * Escapes identifier content for positions already inside quoted templates:
     * strips one surrounding pair of the given quote char, then doubles every
     * embedded quote char.
     */
    public static String escapeIdentifier(String identifier) {
        return escapeIdentifier(identifier, '"');
    }

    /**
     * Dialect-parameterized content escaping for positions already inside quoted
     * templates.
     */
    public static String escapeIdentifier(String identifier, char quote) {
        if (identifier == null) {
            return "";
        }
        String q = String.valueOf(quote);
        String stripped = identifier;
        if (stripped.length() >= 2 && stripped.startsWith(q) && stripped.endsWith(q)) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        return StringUtils.replace(stripped, q, q + q);
    }

    /**
     * Quotes an identifier with the given dialect quote char: strips one surrounding
     * pair of that quote, then doubles every embedded quote char. Blank input is
     * returned unchanged.
     */
    public static String quoteIdentifier(String name, char quote) {
        if (StringUtils.isBlank(name)) {
            return name;
        }
        String q = String.valueOf(quote);
        String identifier = name;
        if (identifier.length() >= 2 && identifier.startsWith(q) && identifier.endsWith(q)) {
            identifier = identifier.substring(1, identifier.length() - 1);
        }
        return q + identifier.replace(q, q + q) + q;
    }
}
