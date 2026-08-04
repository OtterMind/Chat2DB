package ai.chat2db.plugin.oceanbase.oracle.identifier;

import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;

/**
 * OceanBase Oracle mode uses Oracle's identifier, case-folding, and literal
 * escaping rules.
 */
public class OceanbaseOracleIdentifierProcessor extends OracleIdentifierProcessor {

    public static final OceanbaseOracleIdentifierProcessor INSTANCE = new OceanbaseOracleIdentifierProcessor();
}
