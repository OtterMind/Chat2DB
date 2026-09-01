package ai.chat2db.plugin.dm.parser.visitor;

import ai.chat2db.community.domain.api.enums.parser.IdentifierTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.StatementValidTypeEnum;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.StatementContext;
import ai.chat2db.community.domain.api.model.parser.statement.insert.InsertRowTokenRange;
import ai.chat2db.community.domain.api.model.parser.token.Identifier;
import ai.chat2db.plugin.dm.parser.base.DMParser;
import ai.chat2db.plugin.dm.parser.base.DMParserBaseVisitor;
import ai.chat2db.spi.util.InsertValueTokenUtil;
import ai.chat2db.spi.util.SqlCommentUtil;
import ai.chat2db.spi.util.SqlStringUtil;
import ai.chat2db.spi.util.TokenUtil;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

@Slf4j
public class DMParserVisitor extends DMParserBaseVisitor<Void> {

    private final StatementContext context;

    public DMParserVisitor(StatementContext context) {
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
            String sql = "";
            if (StringUtils.isNotBlank(sqlComment)) {
                sql = "--" + " " + sqlComment + "\n";
            }
            sql += commonTokenStream.getText(child.getSourceInterval());
            statement.setSql(sql);
            statement.setFirstToken(child.getStart());
            statement.setLastToken(child.getStop());
            context.addStatement(statement);
            visit(child);
        }
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
    public Void visitAlter_table(DMParser.Alter_tableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_TABLE.name());
        DMParser.Tableview_nameContext tableviewNameContext = ctx.tableview_name();
        visitTableViewName(tableviewNameContext, IdentifierTypeEnum.TABLE);
        return null;
    }

    @Override
    public Void visitSelect_statement(DMParser.Select_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.SELECT.name());
        return super.visitSelect_statement(ctx);
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
    public Void visitFunction_name(DMParser.Function_nameContext ctx) {
        Try.run(() -> {
            Token start = ctx.getStart();
            Token stop = ctx.getStop();
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return;
            }
            if (start.getTokenIndex() == stop.getTokenIndex()) {
                currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                        IdentifierTypeEnum.FUNCTION.name(), start);
            } else {
                String schemaText = SqlStringUtil.removeQuote(start.getText());
                currentStatement.addIdentifier(schemaText,
                        IdentifierTypeEnum.SCHEMA.name(), start);
                Identifier identifier = new Identifier();
                String functionText = SqlStringUtil.removeQuote(stop.getText());
                identifier.setIdentifierName(functionText);
                identifier.setIdentifierType(IdentifierTypeEnum.FUNCTION.name());
                identifier.setFirstToken(stop);
                identifier.setIdentifierSchema(schemaText);
                currentStatement.addIdentifier(identifier);
            }
        }).onFailure(e -> {
            log.error("visitFunction_name error", e);
        });
        return super.visitFunction_name(ctx);
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
    public Void visitProcedure_name(DMParser.Procedure_nameContext ctx) {
        Try.run(() -> {
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return;
            }
            Token start = ctx.getStart();
            Token stop = ctx.getStop();
            if (start.getTokenIndex() == stop.getTokenIndex()) {
                currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                        IdentifierTypeEnum.PROCEDURE.name(), start);
            } else {
                String schemaText = SqlStringUtil.removeQuote(start.getText());

                currentStatement.addIdentifier(schemaText,
                        IdentifierTypeEnum.SCHEMA.name(), start);
                String procedureText = SqlStringUtil.removeQuote(stop.getText());
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(procedureText);
                identifier.setIdentifierType(IdentifierTypeEnum.PROCEDURE.name());
                identifier.setFirstToken(stop);
                identifier.setIdentifierSchema(schemaText);

                currentStatement.addIdentifier(identifier);
            }
        }).onFailure(e -> {
            log.error("visitProcedure_name error", e);
        });
        return super.visitProcedure_name(ctx);
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
    public Void visitTrigger_name(DMParser.Trigger_nameContext ctx) {
        Try.run(() -> {
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return;
            }
            Token start = ctx.getStart();
            Token stop = ctx.getStop();
            if (start.getTokenIndex() == stop.getTokenIndex()) {
                currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                        IdentifierTypeEnum.TRIGGER.name(), start);
            } else {
                String schemaText = SqlStringUtil.removeQuote(start.getText());

                currentStatement.addIdentifier(schemaText,
                        IdentifierTypeEnum.SCHEMA.name(), start);
                String triggerText = SqlStringUtil.removeQuote(stop.getText());
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(triggerText);
                identifier.setIdentifierType(IdentifierTypeEnum.TRIGGER.name());
                identifier.setFirstToken(stop);
                identifier.setIdentifierSchema(schemaText);

                currentStatement.addIdentifier(identifier);
            }
        }).onFailure(e -> {
            log.error("visitTrigger_name error", e);
        });
        return super.visitTrigger_name(ctx);
    }

    @Override
    public Void visitCreate_index(DMParser.Create_indexContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_INDEX.name());
        DMParser.Table_index_clauseContext tableIndexClauseContext = ctx.table_index_clause();
        if (Objects.nonNull(tableIndexClauseContext)) {
            DMParser.Tableview_nameContext tableviewNameContext = tableIndexClauseContext.tableview_name();
            visitTableViewName(tableviewNameContext, IdentifierTypeEnum.TABLE);
        }
        return super.visitCreate_index(ctx);
    }

    @Override
    public Void visitIndex_name(DMParser.Index_nameContext ctx) {
        Try.run(() -> {
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return;
            }
            Token start = ctx.getStart();
            Token stop = ctx.getStop();
            if (start.getTokenIndex() == stop.getTokenIndex()) {
                currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                        IdentifierTypeEnum.INDEX.name(), start);
            } else {
                String tableText = SqlStringUtil.removeQuote(start.getText());

                currentStatement.addIdentifier(tableText,
                        IdentifierTypeEnum.TABLE.name(), start);
                String indexText = SqlStringUtil.removeQuote(stop.getText());
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(indexText);
                identifier.setIdentifierType(IdentifierTypeEnum.INDEX.name());
                identifier.setFirstToken(stop);
                identifier.setIdentifierTable(tableText);

                currentStatement.addIdentifier(identifier);
            }
        }).onFailure(e -> {
            log.error("visitIndex_name error", e);
        });
        return super.visitIndex_name(ctx);
    }

    private void visitTableViewName(DMParser.Tableview_nameContext ctx, IdentifierTypeEnum identifierTypeEnum) {
        Try.run(() -> {
            if (Objects.nonNull(ctx)) {
                Token start = ctx.getStart();
                Token stop = ctx.getStop();
                Statement currentStatement = context.getCurrentStatement();
                if (Objects.isNull(currentStatement)) {
                    return;
                }
                if (start.getTokenIndex() == stop.getTokenIndex()) {
                    currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                            identifierTypeEnum.name(), start);
                } else {
                    String schemaText = SqlStringUtil.removeQuote(start.getText());

                    currentStatement.addIdentifier(schemaText,
                            IdentifierTypeEnum.SCHEMA.name(), start);
                    String tableViewText = SqlStringUtil.removeQuote(stop.getText());
                    Identifier identifier = new Identifier();
                    identifier.setIdentifierName(tableViewText);
                    identifier.setIdentifierType(identifierTypeEnum.name());
                    identifier.setFirstToken(stop);
                    identifier.setIdentifierSchema(schemaText);

                    currentStatement.addIdentifier(identifier);
                }
            }
        }).onFailure(e -> log.error("visitTableViewName error", e));

    }


    @Override
    public Void visitCreate_user(DMParser.Create_userContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_USER.name());
        DMParser.User_object_nameContext userObjectNameContext = ctx.user_object_name();
        if (Objects.nonNull(userObjectNameContext)) {
            Token start = userObjectNameContext.getStart();
            currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                    IdentifierTypeEnum.USER.name(), start);
        }
        return super.visitCreate_user(ctx);
    }


    @Override
    public Void visitCreate_view(DMParser.Create_viewContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_VIEW.name());
        Try.run(() -> {
            DMParser.Schema_nameContext schemaNameContext = ctx.schema_name();
            String schemaText = null;
            if (Objects.nonNull(schemaNameContext)) {
                Token start = schemaNameContext.getStart();
                schemaText = SqlStringUtil.removeQuote(start.getText());
                currentStatement.addIdentifier(schemaText,
                        IdentifierTypeEnum.SCHEMA.name(), start);
            }
            DMParser.Id_expressionContext idExpressionContext = ctx.id_expression(0);
            if (Objects.nonNull(idExpressionContext)) {
                Token start = idExpressionContext.getStart();
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(SqlStringUtil.removeQuote(start.getText()));
                identifier.setIdentifierType(IdentifierTypeEnum.VIEW.name());
                identifier.setFirstToken(start);
                identifier.setIdentifierSchema(schemaText);
                currentStatement.addIdentifier(identifier);
            }
        }).onFailure(e -> {
            log.error("visitCreate_view error", e);
        });

        return super.visitCreate_view(ctx);
    }


    @Override
    public Void visitCreate_role(DMParser.Create_roleContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_ROLE.name());
        DMParser.Role_nameContext roleNameContext = ctx.role_name();
        if (Objects.nonNull(roleNameContext)) {
            Token start = roleNameContext.getStart();
            currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                    IdentifierTypeEnum.ROLE.name(), start);
        }
        return super.visitCreate_role(ctx);
    }

    @Override
    public Void visitCreate_table(DMParser.Create_tableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_TABLE.name());
        return super.visitCreate_table(ctx);
    }

    @Override
    public Void visitTable_name(DMParser.Table_nameContext ctx) {
        Try.run(() -> {
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return;
            }
            Token start = ctx.getStart();
            Token stop = ctx.getStop();
            if (start.getTokenIndex() == stop.getTokenIndex()) {
                currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                        IdentifierTypeEnum.TABLE.name(), start);
            } else {
                String schemaText = SqlStringUtil.removeQuote(start.getText());

                currentStatement.addIdentifier(schemaText,
                        IdentifierTypeEnum.SCHEMA.name(), start);
                String tableText = SqlStringUtil.removeQuote(stop.getText());
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(tableText);
                identifier.setIdentifierType(IdentifierTypeEnum.TABLE.name());
                identifier.setFirstToken(stop);
                identifier.setIdentifierSchema(schemaText);

                currentStatement.addIdentifier(identifier);
            }
        }).onFailure(e -> {
            log.error("visitTable_name error", e);
        });

        return super.visitTable_name(ctx);
    }

    @Override
    public Void visitSchema_name(DMParser.Schema_nameContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        Token start = ctx.getStart();
        currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                IdentifierTypeEnum.SCHEMA.name(), start);
        return super.visitSchema_name(ctx);
    }

    @Override
    public Void visitCreate_database(DMParser.Create_databaseContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_DATABASE.name());
        return super.visitCreate_database(ctx);
    }

    @Override
    public Void visitDatabase_name(DMParser.Database_nameContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        Token start = ctx.getStart();
        currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                IdentifierTypeEnum.SCHEMA.name(), start);
        return super.visitDatabase_name(ctx);
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
        DMParser.Role_nameContext roleNameContext = ctx.role_name();
        if (Objects.nonNull(roleNameContext)) {
            Token start = roleNameContext.getStart();
            currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                    IdentifierTypeEnum.ROLE.name(), start);
        }
        return super.visitDrop_role(ctx);
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
        DMParser.User_object_nameContext userObjectNameContext = ctx.user_object_name();
        if (Objects.nonNull(userObjectNameContext)) {
            Token start = userObjectNameContext.getStart();
            currentStatement.addIdentifier(SqlStringUtil.removeQuote(start.getText()),
                    IdentifierTypeEnum.USER.name(), start);
        }
        return super.visitDrop_user(ctx);
    }

    @Override
    public Void visitDrop_index(DMParser.Drop_indexContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_INDEX.name());
        return super.visitDrop_index(ctx);
    }


    @Override
    public Void visitDrop_table(DMParser.Drop_tableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_TABLE.name());
        DMParser.Tableview_nameContext tableviewNameContext = ctx.tableview_name();
        visitTableViewName(tableviewNameContext, IdentifierTypeEnum.TABLE);
        return super.visitDrop_table(ctx);
    }


    @Override
    public Void visitDrop_view(DMParser.Drop_viewContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_VIEW.name());
        DMParser.Tableview_nameContext tableviewNameContext = ctx.tableview_name();
        visitTableViewName(tableviewNameContext, IdentifierTypeEnum.VIEW);
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
        Try.run(() -> {
            DMParser.Parameter_valueContext parameterValueContext = alterSessionSetClauseContext.parameter_value(0);
            if (Objects.nonNull(parameterValueContext)) {
                String schemaText = SqlStringUtil.removeQuote(parameterValueContext.getText());
                currentStatement.addIdentifier(schemaText,
                        IdentifierTypeEnum.SCHEMA.name(), parameterValueContext.getStart());
            }
        }).onFailure(e -> {
            log.error("alter session error", e);
        });
        return super.visitAlter_session(ctx);
    }

    @Override
    public Void visitDml_table_expression_clause(DMParser.Dml_table_expression_clauseContext ctx) {
        DMParser.Tableview_nameContext tableviewNameContext = ctx.tableview_name();
        visitTableViewName(tableviewNameContext, IdentifierTypeEnum.TABLE);
        return super.visitDml_table_expression_clause(ctx);
    }

    @Override
    public Void visitUpdate_statement(DMParser.Update_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.UPDATE.name());
        return super.visitUpdate_statement(ctx);
    }

    @Override
    public Void visitInsert_statement(DMParser.Insert_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.INSERT.name());
        visitInsertValueMappings(context, ctx);
        return super.visitInsert_statement(ctx);
    }

    static void visitInsertValueMappings(StatementContext context, DMParser.Insert_statementContext ctx) {
        Try.run(() -> {
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement) || Objects.isNull(ctx)) {
                return;
            }
            DMParser.Single_table_insertContext singleTableInsertContext = ctx.single_table_insert();
            if (Objects.isNull(singleTableInsertContext)) {
                return;
            }
            DMParser.Insert_into_clauseContext insertIntoClauseContext = singleTableInsertContext.insert_into_clause();
            DMParser.Values_clauseContext valuesClauseContext = singleTableInsertContext.values_clause();
            if (Objects.isNull(insertIntoClauseContext)) {
                return;
            }
            DMParser.Paren_column_listContext columnListContext = insertIntoClauseContext.paren_column_list();
            DMParser.ExpressionsContext expressionsContext = Objects.nonNull(valuesClauseContext)
                    ? valuesClauseContext.expressions() : null;
            if (Objects.isNull(columnListContext) || Objects.isNull(columnListContext.column_list())) {
                return;
            }
            List<DMParser.Column_nameContext> columnContexts = columnListContext.column_list().column_name();
            if (CollectionUtils.isEmpty(columnContexts)) {
                return;
            }
            List<InsertRowTokenRange> rowTokenRanges = getInsertRowTokenRanges(context, ctx, valuesClauseContext);
            if (rowTokenRanges.isEmpty()) {
                return;
            }
            Token rowFirstToken = rowTokenRanges.get(0).getFirstToken();
            Token rowLastToken = rowTokenRanges.get(0).getLastToken();
            List<DMParser.ExpressionContext> valueContexts = Objects.nonNull(expressionsContext)
                    ? expressionsContext.expression() : List.of();

            int columnSize = Math.min(columnContexts.size(), valueContexts.size());
            for (int columnIndex = 0; columnIndex < columnSize; columnIndex++) {
                DMParser.Column_nameContext columnContext = columnContexts.get(columnIndex);
                DMParser.ExpressionContext expressionContext = valueContexts.get(columnIndex);
                if (Objects.isNull(columnContext) || Objects.isNull(expressionContext)) {
                    continue;
                }
                currentStatement.addInsertValueMapping(columnContext.getStart(), columnContext.getStop(),
                        expressionContext.getStart(), expressionContext.getStop(), rowFirstToken, rowLastToken,
                        0, columnIndex);
            }
            for (int columnIndex = columnSize; columnIndex < columnContexts.size(); columnIndex++) {
                DMParser.Column_nameContext columnContext = columnContexts.get(columnIndex);
                if (Objects.isNull(columnContext)) {
                    continue;
                }
                currentStatement.addUnmappedInsertColumn(columnContext.getStart(), columnContext.getStop(),
                        rowFirstToken, rowLastToken, 0, columnIndex);
            }
            for (int valueIndex = columnSize; valueIndex < valueContexts.size(); valueIndex++) {
                DMParser.ExpressionContext expressionContext = valueContexts.get(valueIndex);
                if (Objects.isNull(expressionContext)) {
                    continue;
                }
                currentStatement.addUnmappedInsertValue(expressionContext.getStart(), expressionContext.getStop(),
                        rowFirstToken, rowLastToken, 0, valueIndex);
            }
        }).onFailure(e -> log.error("oracle visitInsertValueMappings error", e));
    }

    private static List<InsertRowTokenRange> getInsertRowTokenRanges(StatementContext context,
                                                                     DMParser.Insert_statementContext ctx,
                                                                     DMParser.Values_clauseContext valuesClauseContext) {
        List<InsertRowTokenRange> rowTokenRanges = List.of();
        if (Objects.nonNull(valuesClauseContext)) {
            Token rowFirstToken = Objects.nonNull(valuesClauseContext.LEFT_PAREN())
                    ? valuesClauseContext.LEFT_PAREN().getSymbol() : valuesClauseContext.getStart();
            Token rowLastToken = Objects.nonNull(valuesClauseContext.RIGHT_PAREN())
                    ? valuesClauseContext.RIGHT_PAREN().getSymbol() : valuesClauseContext.getStop();
            rowTokenRanges = InsertValueTokenUtil.singleRowTokenRange(rowFirstToken, rowLastToken);
        }
        if (!rowTokenRanges.isEmpty()) {
            return rowTokenRanges;
        }
        return InsertValueTokenUtil.searchValuesRowTokenRanges(context, ctx.getStart(), ctx.getStop());
    }

    @Override
    public Void visitDelete_statement(DMParser.Delete_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DELETE.name());
        return super.visitDelete_statement(ctx);
    }

    @Override
    public Void visitColumn_name(DMParser.Column_nameContext ctx) {
        Try.of(() -> {
            DMParser.IdentifierContext identifier = ctx.identifier();
            if (Objects.isNull(identifier)) {
                return null;
            }
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            List<DMParser.Id_expressionContext> idExpressionContexts = ctx.id_expression();
            if (CollectionUtils.isEmpty(idExpressionContexts)) {
                currentStatement.addIdentifier(SqlStringUtil.removeQuote(identifier.getText()),
                        IdentifierTypeEnum.COLUMN.name(), identifier.getStart());
            } else {
                String schemaText = null, tableText = null, columnText = null;
                Token databaseToken = null, tableToken = null, columnToken = null;
                if (idExpressionContexts.size() == 2) {
                    databaseToken = identifier.getStart();
                    tableToken = idExpressionContexts.get(0).getStop();
                    columnToken = idExpressionContexts.get(1).getStop();
                    schemaText = SqlStringUtil.removeQuote(SqlStringUtil.removeQuote(identifier.getText()));
                    tableText = SqlStringUtil.removeQuote(SqlStringUtil.removeQuote(idExpressionContexts.get(0).getText()));
                    columnText = SqlStringUtil.removeQuote(SqlStringUtil.removeQuote(idExpressionContexts.get(1).getText()));
                } else if (idExpressionContexts.size() == 1) {
                    tableToken = identifier.getStop();
                    columnToken = idExpressionContexts.get(0).getStop();
                    tableText = SqlStringUtil.removeQuote(SqlStringUtil.removeQuote(identifier.getText()));
                    columnText = SqlStringUtil.removeQuote(SqlStringUtil.removeQuote(idExpressionContexts.get(0).getText()));
                }

                if (Objects.nonNull(databaseToken)) {

                    currentStatement.addIdentifier(schemaText,
                            IdentifierTypeEnum.SCHEMA.name(), databaseToken);
                }
                if (Objects.nonNull(tableToken)) {
                    Identifier identifier1 = new Identifier();
                    identifier1.setIdentifierName(tableText);
                    identifier1.setIdentifierType(IdentifierTypeEnum.TABLE.name());
                    identifier1.setFirstToken(tableToken);
                    identifier1.setIdentifierDatabase(schemaText);

                    currentStatement.addIdentifier(identifier1);
                }
                if (Objects.nonNull(columnToken)) {
                    Identifier identifier1 = new Identifier();
                    identifier1.setIdentifierName(columnText);
                    identifier1.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
                    identifier1.setFirstToken(columnToken);
                    identifier1.setIdentifierDatabase(schemaText);
                    identifier1.setIdentifierTable(tableText);
                    currentStatement.addIdentifier(identifier1);
                }
            }
            return null;
        }).onFailure(e -> {
            log.error("visitColumn_name error", e);
        });

        return null;
    }


    @Override
    public Void visitSelect_list_elements(DMParser.Select_list_elementsContext ctx) {
        Try.of(() -> {
            DMParser.Column_aliasContext columnAliasContext = ctx.column_alias();
            DMParser.ExpressionContext expression = ctx.expression();
            if (Objects.isNull(expression)) {
                return null;
            }
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            CommonTokenStream commonTokenStream = context.getCommonTokenStream();
            Token database = null, table = null, column = null, alias = null;
            String schemaText = null, tableText = null, columnText = null, aliasText = null;
            List<Token> defaultChannelToken = TokenUtil.getParserRuleTokensOnDefault(commonTokenStream, expression);
            int dotCount = defaultChannelToken.stream()
                    .mapToInt(token -> (int) token.getText().chars().filter(ch -> ch == '.').count())
                    .sum();
            int defaultTokenSize = defaultChannelToken.size();
            if (dotCount == 0) {
                if (defaultTokenSize == 2) {
                    alias = defaultChannelToken.get(1);
                    aliasText = SqlStringUtil.removeQuote(alias.getText());
                }
                if (defaultTokenSize > 0) {
                    column = defaultChannelToken.get(0);
                    columnText = SqlStringUtil.removeQuote(column.getText());
                }

            } else if (dotCount == 1) {
                if (defaultTokenSize - 1 == 3) {
                    alias = defaultChannelToken.get(3);
                    aliasText = SqlStringUtil.removeQuote(alias.getText());
                }
                if (defaultTokenSize > 2) {
                    table = defaultChannelToken.get(0);
                    column = defaultChannelToken.get(2);
                    tableText = SqlStringUtil.removeQuote(table.getText());
                    columnText = SqlStringUtil.removeQuote(column.getText());
                }
            } else if (dotCount == 2) {
                if (defaultTokenSize - 2 == 4) {
                    alias = defaultChannelToken.get(5);
                    aliasText = SqlStringUtil.removeQuote(alias.getText());
                }
                if (defaultTokenSize > 4) {
                    database = defaultChannelToken.get(0);
                    table = defaultChannelToken.get(2);
                    column = defaultChannelToken.get(4);
                    schemaText = SqlStringUtil.removeQuote(database.getText());
                    tableText = SqlStringUtil.removeQuote(table.getText());
                    columnText = SqlStringUtil.removeQuote(column.getText());
                }
            }
            if (Objects.isNull(alias) && Objects.nonNull(columnAliasContext)) {
                alias = columnAliasContext.stop;
                aliasText = SqlStringUtil.removeQuote(columnAliasContext.getText());
            }
            if (Objects.nonNull(database)) {
                currentStatement.addIdentifier(schemaText,
                        IdentifierTypeEnum.SCHEMA.name(), database);
            }
            if (Objects.nonNull(table)) {
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(tableText);
                identifier.setIdentifierType(IdentifierTypeEnum.TABLE.name());
                identifier.setFirstToken(table);
                identifier.setIdentifierSchema(schemaText);

                currentStatement.addIdentifier(identifier);
            }
            if (Objects.nonNull(column)) {
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(columnText);
                identifier.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
                identifier.setFirstToken(column);
                identifier.setIdentifierSchema(schemaText);
                identifier.setIdentifierTable(tableText);
                if (Objects.nonNull(alias)) {
                    identifier.setIdentifierAlias(aliasText);
                    identifier.setLastToken(alias);
                }
                currentStatement.addIdentifier(identifier);
            }
            return null;

        }).onFailure(e -> {
            log.error("visit select list error", e);
        });

        return null;
    }


    @Override
    public Void visitTable_ref_aux(DMParser.Table_ref_auxContext ctx) {
        if (ctx.getStart().getText().equalsIgnoreCase("(")) {
            return super.visitTable_ref_aux(ctx);
        }
        Try.of(() -> {
            DMParser.Table_ref_aux_internalContext tableRefAuxInternalContext = ctx.table_ref_aux_internal();
            if (Objects.isNull(tableRefAuxInternalContext)) {
                return null;
            }
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            CommonTokenStream commonTokenStream = context.getCommonTokenStream();
            Token database = null, table, alias = null;
            String schemaText = null, tableText, aliasText = null;
            DMParser.Table_aliasContext tableAliasContext = ctx.table_alias();
            if (Objects.nonNull(tableAliasContext)) {
                alias = tableAliasContext.stop;
                aliasText = SqlStringUtil.removeQuote(tableAliasContext.getText());
            }
            List<Token> defaultChannelTokens = TokenUtil.getParserRuleTokensOnDefault(commonTokenStream, tableRefAuxInternalContext);
            int dotCount = defaultChannelTokens.stream()
                    .mapToInt(token -> (int) token.getText().chars().filter(ch -> ch == '.').count())
                    .sum();
            if (dotCount == 1) {
                database = defaultChannelTokens.get(0);
                schemaText = SqlStringUtil.removeQuote(database.getText());
            }
            table = defaultChannelTokens.get(defaultChannelTokens.size() - 1);
            tableText = SqlStringUtil.removeQuote(table.getText());

            if (Objects.nonNull(database)) {

                currentStatement.addIdentifier(schemaText,
                        IdentifierTypeEnum.SCHEMA.name(), database);
            }
            Identifier identifier = new Identifier();
            identifier.setIdentifierName(tableText);
            identifier.setIdentifierType(IdentifierTypeEnum.TABLE.name());
            identifier.setFirstToken(table);
            identifier.setIdentifierSchema(schemaText);
            if (Objects.nonNull(alias)) {
                identifier.setIdentifierAlias(aliasText);
                identifier.setLastToken(alias);
            }
            currentStatement.addIdentifier(identifier);
            return null;
        }).onFailure(e -> log.error("visit table ref aux error", e));
        return null;
    }

    @Override
    public Void visitJoin_on_part(DMParser.Join_on_partContext ctx) {
        Try.of(() -> {
            DMParser.ConditionContext condition = ctx.condition();
            if (Objects.isNull(condition)) {
                return null;
            }
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            CommonTokenStream commonTokenStream = context.getCommonTokenStream();
            List<Token> defaultChannelTokens = TokenUtil.getParserRuleTokensOnDefault(commonTokenStream, condition);
            List<List<Token>> tokenLists = TokenUtil.splitTokensBySymbolText(defaultChannelTokens, "=");
            for (List<Token> tokenList : tokenLists) {
                int size = tokenList.size();
                if (size == 5) {
                    Token database = tokenList.get(0);
                    Token table = tokenList.get(2);
                    Token column = tokenList.get(4);
                    String schemaText = SqlStringUtil.removeQuote(database.getText());
                    String tableText = SqlStringUtil.removeQuote(table.getText());
                    String columnText = SqlStringUtil.removeQuote(column.getText());
                    currentStatement.addIdentifier(schemaText,
                            IdentifierTypeEnum.SCHEMA.name(), database);
                    Identifier identifier = new Identifier();
                    identifier.setIdentifierName(tableText);
                    identifier.setIdentifierType(IdentifierTypeEnum.TABLE.name());
                    identifier.setFirstToken(table);
                    identifier.setIdentifierSchema(schemaText);

                    currentStatement.addIdentifier(identifier);
                    Identifier identifier1 = new Identifier();
                    identifier1.setIdentifierName(columnText);
                    identifier1.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
                    identifier1.setFirstToken(column);
                    identifier1.setIdentifierDatabase(schemaText);
                    identifier1.setIdentifierTable(tableText);
                    currentStatement.addIdentifier(identifier1);
                } else if (size == 3) {
                    Token table = tokenList.get(0);
                    Token column = tokenList.get(2);
                    String tableText = SqlStringUtil.removeQuote(table.getText());
                    String columnText = SqlStringUtil.removeQuote(column.getText());
                    Identifier identifier = new Identifier();
                    identifier.setIdentifierName(tableText);
                    identifier.setIdentifierType(IdentifierTypeEnum.TABLE.name());
                    identifier.setFirstToken(table);

                    currentStatement.addIdentifier(identifier);
                    Identifier identifier1 = new Identifier();
                    identifier1.setIdentifierName(columnText);
                    identifier1.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
                    identifier1.setFirstToken(column);
                    identifier1.setIdentifierTable(tableText);
                    currentStatement.addIdentifier(identifier1);
                }
            }

            return null;
        }).onFailure(e -> {
            log.error("visit join on part error", e);
        });

        return null;
    }


    @Override
    public Void visitColumn_definition(DMParser.Column_definitionContext ctx) {
        Try.of(() -> {
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            DMParser.Column_nameContext columnNameContext = ctx.column_name();
            if (Objects.isNull(columnNameContext)) {
                return null;
            }
            String columnName = context.getText(columnNameContext.getSourceInterval());
            if (StringUtils.isBlank(columnName)) {
                return null;
            }
            String[] split = columnName.split("\\.");
            columnName = split[split.length - 1];
            DMParser.DatatypeContext datatype = ctx.datatype();
            if (Objects.isNull(datatype)) {
                return null;
            }
            String datatypeText = datatype.getText();
            Identifier identifier = new Identifier();
            identifier.setIdentifierName(SqlStringUtil.removeQuote(columnName));
            identifier.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
            identifier.setIdentifierDataType(datatypeText);

            currentStatement.addIdentifier(identifier);
            return null;
        }).onFailure(e -> log.error("visit column definition error", e));

        return null;
    }

    @Override
    public Void visitCall_statement(DMParser.Call_statementContext ctx) {
        Try.of(() -> {
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            DMParser.Routine_nameContext routineNameContext = ctx.routine_name(0);
            if (Objects.isNull(routineNameContext)) {
                return null;
            }
            Token start = routineNameContext.getStart();
            Token stop = routineNameContext.getStop();
            if (start.getTokenIndex() != stop.getTokenIndex()) {
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(SqlStringUtil.removeQuote(start.getText()));
                identifier.setIdentifierType(IdentifierTypeEnum.SCHEMA.name());
                identifier.setFirstToken(start);

                currentStatement.addIdentifier(identifier);
            }
            Identifier identifier = new Identifier();
            identifier.setIdentifierName(SqlStringUtil.removeQuote(stop.getText()));
            identifier.setIdentifierType(IdentifierTypeEnum.PROCEDURE.name());
            identifier.setFirstToken(stop);
            currentStatement.addIdentifier(identifier);
            return null;
        }).onFailure(e -> log.error("visit call statement error", e));
        return null;
    }
}
