package ai.chat2db.plugin.redshift.identifier;

import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;

/**
 * Redshift follows PostgreSQL identifier folding, quoting, reserved-word, and
 * string-literal rules. Keep a dialect-specific instance while reusing the shared
 * PostgreSQL contract so completion and generated SQL cannot drift apart.
 */
public class RedshiftIdentifierProcessor extends PostgreSQLIdentifierProcessor {

    public static final RedshiftIdentifierProcessor INSTANCE = new RedshiftIdentifierProcessor();
}
