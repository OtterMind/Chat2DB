package ai.chat2db.spi.syntax;

import ai.chat2db.spi.ISQLParser;
import ai.chat2db.spi.ISqlSyntaxPlugin;

/** Test-only syntax plugin registered via META-INF/services on the test classpath. */
public class RegistryProbeSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return "REGISTRY_PROBE";
    }

    @Override
    public ISQLParser getSQLParser() {
        return null;
    }
}
