package ai.chat2db.spi;

import org.apache.commons.lang3.StringUtils;

/**
 * Identifier processor whose quote characters come from configuration, so a
 * configuration-only database can declare its dialect's quoting without a
 * dedicated Java plugin. The spec is either a single quote string used on
 * both sides (for example {@code `}) or an {@code open:close} pair (for
 * example {@code [:]}). An embedded closing quote is escaped by doubling.
 */
public class ConfigurableSQLIdentifierProcessor extends DefaultSQLIdentifierProcessor {

    private final String open;
    private final String close;

    private ConfigurableSQLIdentifierProcessor(String open, String close) {
        this.open = open;
        this.close = close;
    }

    /**
     * Parses a quote spec into a processor.
     *
     * @param spec single quote string, or an {@code open:close} pair.
     * @return the processor, or null when the spec is blank or malformed.
     */
    public static ConfigurableSQLIdentifierProcessor fromSpec(String spec) {
        String trimmed = StringUtils.trimToNull(spec);
        if (trimmed == null) {
            return null;
        }
        int separator = trimmed.indexOf(':');
        if (separator > 0 && separator < trimmed.length() - 1) {
            String open = trimmed.substring(0, separator);
            String close = trimmed.substring(separator + 1);
            return new ConfigurableSQLIdentifierProcessor(open, close);
        }
        if (separator < 0) {
            return new ConfigurableSQLIdentifierProcessor(trimmed, trimmed);
        }
        return null;
    }

    public String getOpen() {
        return open;
    }

    public String getClose() {
        return close;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        if (isValidIdentifier(identifier)) {
            return identifier;
        }
        return open + identifier.replace(close, close + close) + close;
    }

    @Override
    public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
        return quoteIdentifier(identifier);
    }

    @Override
    public String quoteIdentifierIgnoreCase(String identifier) {
        return quoteIdentifier(identifier);
    }

    @Override
    public boolean isQuoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return false;
        }
        return identifier.startsWith(open) && identifier.endsWith(close)
                && identifier.length() >= open.length() + close.length();
    }

    @Override
    public String removeIdentifierQuote(String identifier) {
        if (!isQuoteIdentifier(identifier)) {
            return identifier;
        }
        String body = identifier.substring(open.length(), identifier.length() - close.length());
        return body.replace(close + close, close);
    }
}
