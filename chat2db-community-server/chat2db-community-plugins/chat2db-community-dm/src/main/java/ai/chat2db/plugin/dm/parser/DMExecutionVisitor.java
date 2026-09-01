package ai.chat2db.plugin.dm.parser;

import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.plugin.dm.parser.base.DMParser;
import ai.chat2db.plugin.dm.parser.base.DMParserBaseVisitor;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

public class DMExecutionVisitor extends DMParserBaseVisitor<DMExecutableSql> {

    private final CommonTokenStream tokens;

    private final String originalSql;

    private DMExecutableSql result;

    public DMExecutionVisitor(CommonTokenStream tokens, String originalSql) {
        this.tokens = tokens;
        this.originalSql = originalSql;
    }

    @Override
    public DMExecutableSql visitSql_script(DMParser.Sql_scriptContext ctx) {
        visitChildren(ctx);
        return result == null ? new DMExecutableSql(SqlTypeEnum.OTHER.name(), originalSql, originalSql) : result;
    }

    @Override
    public DMExecutableSql visitExplain_statement(DMParser.Explain_statementContext ctx) {
        ParserRuleContext innerStatement = firstNonNull(
                ctx.select_statement(),
                ctx.update_statement(),
                ctx.delete_statement(),
                ctx.insert_statement(),
                ctx.merge_statement());
        String executableSql = innerStatement == null ? originalSql : tokens.getText(innerStatement.getSourceInterval());
        result = new DMExecutableSql(SqlTypeEnum.EXPLAIN.name(), originalSql, executableSql);
        return result;
    }

    private ParserRuleContext firstNonNull(ParserRuleContext... candidates) {
        for (ParserRuleContext candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
}
