package ai.chat2db.plugin.dm.parser;

import ai.chat2db.spi.parser.dialect.AbstractSQLDialect;
import ai.chat2db.plugin.dm.parser.base.DMLexer;
import ai.chat2db.plugin.dm.parser.rule.manager.DMRuleManager;
import ai.chat2db.spi.IRuleManager;

import java.util.Set;

public class DMDialect extends AbstractSQLDialect {
    private static final IRuleManager ORACLE_RULE_MANAGER = new DMRuleManager();

    private static final Set<Integer> ORACLE_COMMENT_TOKENS = Set.of(DMLexer.SINGLE_LINE_COMMENT, DMLexer.MULTI_LINE_COMMENT);

    private static final Set<String> SQL_START_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "ALTER", "DROP",
            "ADMINISTER", "ANALYZE", "BEGIN", "COMMIT", "CREATE",
            "DECLARE", "EXECUTE", "GRANT", "RENAME", "REVOKE",
            "SAVEPOINT", "TRUNCATE", "LOCK", "NOAUDIT", "PURGE",
            "EXPLAIN", "FLASHBACK", "ASSOCIATE", "AUDIT", "MERGE",
            "ROLLBACK", "DISASSOCIATE", ";", "COMMENT",
            "WITH", "EXIT", "PROMPT_MESSAGE", "SHOW", "WHENEVER",
            "TIMING", "START_CMD"
    );

    @Override
    public Set<Integer> getCommentTokens() {
        return ORACLE_COMMENT_TOKENS;
    }

    @Override
    public boolean isComment(int tokenType) {
        return getCommentTokens().contains(tokenType);
    }

    @Override
    public Set<String> getSqlStartKeywords() {
        return SQL_START_KEYWORDS;
    }


    @Override
    public Set<String> getBlockHeaderPrefixes() {
        Set<String> blockHeaderPrefixes = super.getBlockHeaderPrefixes();
        blockHeaderPrefixes.add("COMPOUND");
        return blockHeaderPrefixes;
    }

    @Override
    public Set<String> getIndependentBlockHeaders() {
        Set<String> independentBlockHeaders = super.getIndependentBlockHeaders();
        independentBlockHeaders.add("DECLARE");
        return independentBlockHeaders;
    }

    @Override
    public Set<String> getDependentBlockHeaders() {
        Set<String> dependentBlockHeaders = super.getDependentBlockHeaders();
        dependentBlockHeaders.add("PACKAGE");
        return dependentBlockHeaders;
    }

    @Override
    public Set<String> getInnerBlockPrefix() {
        Set<String> innerBlockPrefix = super.getInnerBlockPrefix();
        innerBlockPrefix.add("AS");
        innerBlockPrefix.add("IS");
        return innerBlockPrefix;
    }

    @Override
    public Set<String> getPackageBodyInnerBlockHeaders() {
        Set<String> packageBodyInnerBlockHeaders = super.getPackageBodyInnerBlockHeaders();
        packageBodyInnerBlockHeaders.add("FUNCTION");
        packageBodyInnerBlockHeaders.add("PROCEDURE");
        return packageBodyInnerBlockHeaders;
    }

    @Override
    public IRuleManager getRuleManager() {
        return ORACLE_RULE_MANAGER;
    }
}
