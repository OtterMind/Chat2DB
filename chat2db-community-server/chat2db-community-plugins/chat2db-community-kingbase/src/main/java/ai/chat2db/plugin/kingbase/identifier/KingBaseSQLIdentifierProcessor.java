package ai.chat2db.plugin.kingbase.identifier;

import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;

/**
 * KingBase uses PostgreSQL-compatible identifier folding, delimiters, reserved
 * words, and string literal escaping.
 */
public class KingBaseSQLIdentifierProcessor extends PostgreSQLIdentifierProcessor {

    public static final KingBaseSQLIdentifierProcessor INSTANCE = new KingBaseSQLIdentifierProcessor();
}
