package ai.chat2db.spi;


/**
 * Normalizes, quotes, and escapes SQL identifiers for a specific database dialect.
 */
public interface ISQLIdentifierProcessor {

    /**
     * Checks whether an identifier is already valid for the dialect without additional quoting.
     *
     * @param identifier identifier text to validate.
     * @return {@code true} when the identifier can be used without quoting; otherwise {@code false}.
     */
    boolean isValidIdentifier(String identifier);

    /**
     * Checks whether an identifier is a reserved keyword for the dialect and database version.
     *
     * @param identifier identifier text to check.
     * @param majorVersion database major version, or {@code null} when version-specific checks are unavailable.
     * @param minorVersion database minor version, or {@code null} when version-specific checks are unavailable.
     * @return {@code true} when the identifier is reserved; otherwise {@code false}.
     */
    boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion);

    /**
     * Quotes an identifier when required by the dialect and database version.
     *
     * @param identifier raw identifier text.
     * @param majorVersion database major version, or {@code null} when version-specific checks are unavailable.
     * @param minorVersion database minor version, or {@code null} when version-specific checks are unavailable.
     * @return identifier text that is safe to use in SQL.
     */
    String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion);


    /**
     * Quotes an identifier using the dialect default version rules.
     *
     * @param identifier raw identifier text.
     * @return identifier text that is safe to use in SQL.
     */
    String quoteIdentifier(String identifier);

    /**
     * Removes dialect quote characters from an identifier when they are present.
     *
     * @param identifier quoted or unquoted identifier text.
     * @return identifier without the outer dialect quote characters.
     */
    String removeIdentifierQuote(String identifier);


    /**
     * Quotes an identifier without applying dialect-specific case conversion.
     * <p>
     * Use this when the caller must preserve the original identifier case.
     *
     * @param identifier raw identifier text.
     * @return quoted identifier text with the original case preserved.
     */
    String quoteIdentifierIgnoreCase(String identifier);

    /**
     * Checks whether an identifier already contains dialect quote characters.
     *
     * @param identifier identifier text to inspect.
     * @return {@code true} when the identifier is quoted; otherwise {@code false}.
     */
    boolean isQuoteIdentifier(String identifier);

    /**
     * Converts an identifier to the dialect default case.
     *
     * @param identifier raw identifier text.
     * @return identifier converted to the dialect default case.
     */
    String convertIdentifierCase(String identifier);


    /**
     * Escapes a string value so it can be safely embedded inside a SQL string literal.
     *
     * @param str raw string value.
     * @return escaped string value without adding the surrounding literal quotes.
     */
    String escapeString(String str);


    /**
     * Quotes an identifier unconditionally, preserving the exact name.
     * <p>
     * Embedded delimiter characters are escaped per the dialect convention.
     * Implementations must satisfy:
     * {@code removeIdentifierQuote(quoteIdentifierAlways(raw)).equals(raw)}.
     * <p>
     * This is a default method for backward compatibility with external
     * implementations that do not yet override it. The default accepts the
     * result of {@link #quoteIdentifierIgnoreCase(String)} only when the
     * implementation reports that result as quoted. Otherwise it fails fast
     * instead of silently violating the always-quote contract.
     *
     * @param identifier raw identifier text.
     * @return unconditionally quoted identifier text with the original case preserved.
     */
    default String quoteIdentifierAlways(String identifier) {
        if (identifier == null) {
            return null;
        }
        String quoted = quoteIdentifierIgnoreCase(identifier);
        if (isQuoteIdentifier(quoted)) {
            return quoted;
        }
        throw new UnsupportedOperationException(
                "quoteIdentifierAlways must be implemented for this SQL dialect");
    }

}
