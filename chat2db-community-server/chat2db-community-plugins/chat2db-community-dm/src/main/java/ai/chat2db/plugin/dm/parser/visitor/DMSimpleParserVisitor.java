package ai.chat2db.plugin.dm.parser.visitor;

import ai.chat2db.community.domain.api.enums.parser.IdentifierTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.StatementValidTypeEnum;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.StatementContext;
import ai.chat2db.community.domain.api.model.parser.token.Identifier;
import ai.chat2db.plugin.dm.parser.base.DMParser;
import ai.chat2db.plugin.dm.parser.base.DMParserBaseVisitor;
import ai.chat2db.spi.util.SqlCommentUtil;
import ai.chat2db.spi.util.SqlStringUtil;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

import java.util.List;
import java.util.Objects;


public class DMSimpleParserVisitor extends DMParserBaseVisitor<Void> {

    private final StatementContext context;

    public DMSimpleParserVisitor(StatementContext context) {
        this.context = context;
    }

    @Override
    public Void visitSql_script(DMParser.Sql_scriptContext ctx) {

        List<DMParser.Unit_statementContext> unitStatement = ctx.unit_statement();
        TokenStream commonTokenStream = context.getCommonTokenStream();
        for (int i = 0; i < unitStatement.size(); i++) {
            DMParser.Unit_statementContext child = unitStatement.get(i);
            Statement statement = new Statement();
            int sqlCommentSearchStartIndex = 0;
            if (i != 0) {
                DMParser.Unit_statementContext unitStatementContext = unitStatement.get(i - 1);
                if (Objects.nonNull(unitStatementContext)) {
                    sqlCommentSearchStartIndex = unitStatementContext.getStop().getTokenIndex();

                }
            }
            int sqlCommentSearchEndIndex = child.getStart().getTokenIndex();
            String sqlComment = SqlCommentUtil.searchSqlComment(sqlCommentSearchStartIndex, sqlCommentSearchEndIndex, commonTokenStream);
            statement.setComment(sqlComment);
            context.setCurrentStatement(statement);
            statement.setStatementType(StatementValidTypeEnum.VALID.name());
            statement.setSql(commonTokenStream.getText(child.getSourceInterval()));
            statement.setFirstToken(child.getStart());
            statement.setLastToken(child.getStop());
            context.addStatement(statement);
            visit(child);
        }
        return null;
    }

    @Override
    public Void visitSelect_statement(DMParser.Select_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.SELECT.name());
        return null;
    }

    @Override
    public Void visitExplain_statement(DMParser.Explain_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.EXPLAIN.name());
        return null;
    }

    @Override
    public Void visitAnonymous_block(DMParser.Anonymous_blockContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ANONYMOUS_BLOCK.name());
        String sql = currentStatement.getSql();
        if (!sql.endsWith(";")) {
            sql = sql + ";";
            currentStatement.setSql(sql);
        }
        return super.visitAnonymous_block(ctx);
    }

    @Override
    public Void visitCreate_function_body(DMParser.Create_function_bodyContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_FUNCTION.name());
        String sql = currentStatement.getSql();
        if (!sql.endsWith(";")) {
            sql = sql + ";";
            currentStatement.setSql(sql);
        }
        return super.visitCreate_function_body(ctx);
    }

    @Override
    public Void visitCreate_procedure_body(DMParser.Create_procedure_bodyContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_PROCEDURE.name());
        String sql = currentStatement.getSql();
        if (!sql.endsWith(";")) {
            sql = sql + ";";
            currentStatement.setSql(sql);
        }
        return super.visitCreate_procedure_body(ctx);
    }

    @Override
    public Void visitCreate_trigger(DMParser.Create_triggerContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_TRIGGER.name());
        String sql = currentStatement.getSql();
        if (!sql.endsWith(";")) {
            sql = sql + ";";
            currentStatement.setSql(sql);
        }
        return super.visitCreate_trigger(ctx);
    }

    @Override
    public Void visitCreate_index(DMParser.Create_indexContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_INDEX.name());
        return null;
    }

    @Override
    public Void visitCreate_user(DMParser.Create_userContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_USER.name());
        currentStatement.addIdentifier(new Identifier());
        return null;
    }


    @Override
    public Void visitCreate_view(DMParser.Create_viewContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_VIEW.name());
        DMParser.Schema_nameContext schemaNameContext = ctx.schema_name();
        String schemaText;
        if (Objects.nonNull(schemaNameContext)) {
            Token start = schemaNameContext.getStart();
            schemaText = SqlStringUtil.removeQuote(start.getText());
            Identifier identifier = new Identifier();
            identifier.setIdentifierSchema(schemaText);
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());

            currentStatement.addIdentifier(identifier);
        } else {
            Identifier identifier = new Identifier();
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
            currentStatement.addIdentifier(identifier);
        }
        return null;
    }

    @Override
    public Void visitCreate_role(DMParser.Create_roleContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_ROLE.name());
        currentStatement.addIdentifier(new Identifier());
        return null;
    }

    @Override
    public Void visitCreate_table(DMParser.Create_tableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_TABLE.name());
        DMParser.Schema_nameContext schemaNameContext = ctx.schema_name();
        if (Objects.nonNull(schemaNameContext)) {
            Token start = schemaNameContext.getStart();
            String schemaText = SqlStringUtil.removeQuote(start.getText());
            Identifier identifier = new Identifier();
            identifier.setIdentifierSchema(schemaText);
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());

            currentStatement.addIdentifier(identifier);
        } else {
            currentStatement.addIdentifier(new Identifier());
        }
        return super.visitCreate_table(ctx);
    }

    @Override
    public Void visitCreate_database(DMParser.Create_databaseContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_DATABASE.name());
        currentStatement.addIdentifier(new Identifier());
        return null;
    }


    @Override
    public Void visitDrop_function(DMParser.Drop_functionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_FUNCTION.name());
        return super.visitDrop_function(ctx);
    }

    @Override
    public Void visitDrop_procedure(DMParser.Drop_procedureContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_PROCEDURE.name());
        return super.visitDrop_procedure(ctx);
    }

    @Override
    public Void visitDrop_role(DMParser.Drop_roleContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_ROLE.name());
        return null;
    }

    @Override
    public Void visitDrop_trigger(DMParser.Drop_triggerContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_TRIGGER.name());
        return super.visitDrop_trigger(ctx);
    }


    @Override
    public Void visitDrop_user(DMParser.Drop_userContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_USER.name());
        currentStatement.addIdentifier(new Identifier());

        return null;
    }

    @Override
    public Void visitDrop_index(DMParser.Drop_indexContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_INDEX.name());
        return null;
    }

    @Override
    public Void visitDrop_table(DMParser.Drop_tableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_TABLE.name());
        DMParser.Tableview_nameContext tableviewNameContext = ctx.tableview_name();
        visitTableViewName(tableviewNameContext);
        return null;
    }


    @Override
    public Void visitDrop_view(DMParser.Drop_viewContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_VIEW.name());
        DMParser.Tableview_nameContext tableviewNameContext = ctx.tableview_name();
        visitTableViewName(tableviewNameContext);
        return super.visitDrop_view(ctx);
    }

    @Override
    public Void visitAlter_session(DMParser.Alter_sessionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        DMParser.Alter_session_set_clauseContext alterSessionSetClauseContext = ctx.alter_session_set_clause();
        if (Objects.isNull(alterSessionSetClauseContext)
                || alterSessionSetClauseContext.parameter_name().isEmpty()
                || !"CURRENT_SCHEMA".equalsIgnoreCase(
                        alterSessionSetClauseContext.parameter_name(0).getText())) {
            return super.visitAlter_session(ctx);
        }
        currentStatement.setType(SqlTypeEnum.SET_SCHEMA.name());
        DMParser.Parameter_valueContext parameterValueContext = alterSessionSetClauseContext.parameter_value(0);
        if (Objects.nonNull(parameterValueContext)) {
            Identifier identifier = new Identifier();
            String schemaText = SqlStringUtil.removeQuote(parameterValueContext.getText());
            identifier.setIdentifierSchema(schemaText);
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
            currentStatement.addIdentifier(identifier);
        }
        return null;
    }

    @Override
    public Void visitUpdate_statement(DMParser.Update_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.UPDATE.name());
        return null;
    }

    @Override
    public Void visitInsert_statement(DMParser.Insert_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.INSERT.name());
        DMParserVisitor.visitInsertValueMappings(context, ctx);
        return null;
    }

    @Override
    public Void visitDelete_statement(DMParser.Delete_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DELETE.name());
        return null;
    }

    private void visitTableViewName(DMParser.Tableview_nameContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return;
        }
        if (Objects.nonNull(ctx)) {
            Token start = ctx.getStart();
            Token stop = ctx.getStop();
            if (start.getTokenIndex() != stop.getTokenIndex()) {
                String schemaText = SqlStringUtil.removeQuote(start.getText());
                Identifier identifier = new Identifier();
                identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
                identifier.setIdentifierSchema(schemaText);
                currentStatement.addIdentifier(identifier);
            } else {
                Identifier identifier = new Identifier();
                identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
                currentStatement.addIdentifier(identifier);
            }

        }
    }

    @Override
    public Void visitFunction_name(DMParser.Function_nameContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        if (start.getTokenIndex() != stop.getTokenIndex()) {
            String schemaText = SqlStringUtil.removeQuote(start.getText());
            Identifier identifier = new Identifier();
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
            identifier.setIdentifierSchema(schemaText);
            currentStatement.addIdentifier(identifier);
        } else {
            Identifier identifier = new Identifier();
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
            currentStatement.addIdentifier(identifier);
        }
        return null;
    }

    @Override
    public Void visitProcedure_name(DMParser.Procedure_nameContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        if (start.getTokenIndex() != stop.getTokenIndex()) {
            String schemaText = SqlStringUtil.removeQuote(start.getText());
            Identifier identifier = new Identifier();
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
            identifier.setIdentifierSchema(schemaText);
            currentStatement.addIdentifier(identifier);
        } else {
            Identifier identifier = new Identifier();
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
            currentStatement.addIdentifier(identifier);
        }
        return null;
    }

    @Override
    public Void visitTrigger_name(DMParser.Trigger_nameContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        if (start.getTokenIndex() != stop.getTokenIndex()) {
            String schemaText = SqlStringUtil.removeQuote(start.getText());
            Identifier identifier = new Identifier();
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
            identifier.setIdentifierSchema(schemaText);
            currentStatement.addIdentifier(identifier);
        } else {
            Identifier identifier = new Identifier();
            identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
            currentStatement.addIdentifier(identifier);
        }
        return null;
    }


}
