package ai.chat2db.plugin.dm.parser.visitor;

import ai.chat2db.community.domain.api.model.parser.statement.StatementContext;
import ai.chat2db.plugin.dm.parser.base.DMParserBaseVisitor;

public class DMCreateTableVisitor extends DMParserBaseVisitor<Void> {

    private StatementContext context;

    public DMCreateTableVisitor(StatementContext context) {
        this.context = context;
    }
}
