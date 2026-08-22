package ai.chat2db.plugin.mysql.parser.visitor;

import ai.chat2db.community.domain.api.enums.parser.IdentifierTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.StatementValidTypeEnum;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.StatementContext;
import ai.chat2db.community.domain.api.model.parser.statement.insert.InsertRowTokenRange;
import ai.chat2db.community.domain.api.model.parser.token.Identifier;
import ai.chat2db.mysql.parser.base.MySqlParser;
import ai.chat2db.mysql.parser.base.MySqlParserBaseVisitor;
import ai.chat2db.plugin.mysql.parser.util.MysqlStringUtil;
import ai.chat2db.spi.util.InsertValueTokenUtil;
import ai.chat2db.spi.util.SqlCommentUtil;
import io.vavr.control.Try;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

public class MysqlParserVisitor extends MySqlParserBaseVisitor<Void> {

    private static final Logger log = LoggerFactory.getLogger(MysqlParserVisitor.class);

    private StatementContext context;

    public StatementContext getContext() {
        return context;
    }

    public void setContext(StatementContext context) {
        this.context = context;
    }


    public MysqlParserVisitor(StatementContext context) {
        this.context = context;
    }

    @Override
    public Void visitSqlStatements(MySqlParser.SqlStatementsContext ctx) {
        List<MySqlParser.SqlStatementContext> sqlStatement = ctx.sqlStatement();
        TokenStream commonTokenStream = context.getCommonTokenStream();
        for (int i = 0; i < sqlStatement.size(); i++) {
            int commentStartIndex = 0;
            if (i != 0) {
                MySqlParser.SqlStatementContext lastChild = sqlStatement.get(i - 1);
                if (Objects.nonNull(lastChild)) {
                    commentStartIndex = lastChild.getStop().getTokenIndex();
                }
            }
            MySqlParser.SqlStatementContext child = sqlStatement.get(i);
            int commentEndIndex = child.getStart().getTokenIndex();
            String sqlComment = SqlCommentUtil.searchSqlComment(commentStartIndex, commentEndIndex, commonTokenStream);
            Statement statement = new Statement();
            statement.setStatementType(StatementValidTypeEnum.VALID.name());
            String sql = "";
            if (StringUtils.isNotBlank(sqlComment)) {
                sql = "--" + " " + sqlComment + "\n";
            }
            sql += commonTokenStream.getText(child.getSourceInterval());
            statement.setSql(sql);
            statement.setFirstToken(child.getStart());
            statement.setLastToken(child.getStop());
            statement.setComment(sqlComment);
            context.setCurrentStatement(statement);
            visit(child);
            context.addStatement(statement);
        }
        return null;
    }
    @Override
    public Void visitParenthesisSelect(MySqlParser.ParenthesisSelectContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.SELECT.name());
        return super.visitParenthesisSelect(ctx);
    }

    @Override
    public Void visitSimpleSelect(MySqlParser.SimpleSelectContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.SELECT.name());
        return super.visitSimpleSelect(ctx);
    }

    @Override
    public Void visitUnionSelect(MySqlParser.UnionSelectContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.SELECT.name());
        return super.visitUnionSelect(ctx);
    }

    @Override
    public Void visitWithLateralStatement(MySqlParser.WithLateralStatementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.SELECT.name());
        return super.visitWithLateralStatement(ctx);
    }


    @Override
    public Void visitInsertStatement(MySqlParser.InsertStatementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.INSERT.name());
        MySqlParser.TableNameContext tableNameContext = ctx.tableReferenceName().tableName();
        visitTableNameContext(tableNameContext);
        visitInsertValueMappings(context, ctx);
        return super.visitInsertStatement(ctx);
    }

    static void visitInsertValueMappings(StatementContext context, MySqlParser.InsertStatementContext ctx) {
        Try.run(() -> {
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement) || Objects.isNull(ctx)) {
                return;
            }
            MySqlParser.ColumnReferenceNameListContext columnListContext = ctx.columnReferenceNameList();
            MySqlParser.InsertStatementValueContext valueContext = ctx.insertStatementValue();
            if (Objects.isNull(columnListContext) || Objects.isNull(valueContext)
                    || (Objects.isNull(valueContext.VALUES()) && Objects.isNull(valueContext.VALUE()))) {
                return;
            }
            List<MySqlParser.ColumnReferenceNameContext> columnContexts = columnListContext.columnReferenceName();
            List<InsertRowTokenRange> rowTokenRanges = InsertValueTokenUtil.buildRowTokenRanges(
                    valueContext.LR_BRACKET(), valueContext.RR_BRACKET());
            if (CollectionUtils.isEmpty(columnContexts) || CollectionUtils.isEmpty(rowTokenRanges)) {
                return;
            }
            List<MySqlParser.InsertValueExpressionsContext> rowContexts = valueContext.insertValueExpressions();
            int expressionRowIndex = 0;

            for (int rowIndex = 0; rowIndex < rowTokenRanges.size(); rowIndex++) {
                Token rowFirstToken = rowTokenRanges.get(rowIndex).getFirstToken();
                Token rowLastToken = rowTokenRanges.get(rowIndex).getLastToken();
                List<MySqlParser.ExpressionOrDefaultContext> valueContexts = List.of();
                if (rowContexts.size() > expressionRowIndex) {
                    MySqlParser.InsertValueExpressionsContext rowContext = rowContexts.get(expressionRowIndex);
                    if (Objects.nonNull(rowContext) && rowContext.getStart().getTokenIndex() > rowFirstToken.getTokenIndex()
                            && rowContext.getStop().getTokenIndex() < rowLastToken.getTokenIndex()) {
                        valueContexts = expressionOrDefaultContexts(rowContext);
                        expressionRowIndex++;
                    }
                }
                int columnSize = Math.min(columnContexts.size(), valueContexts.size());
                for (int columnIndex = 0; columnIndex < columnSize; columnIndex++) {
                    MySqlParser.ColumnReferenceNameContext columnContext = columnContexts.get(columnIndex);
                    MySqlParser.ExpressionOrDefaultContext expressionContext = valueContexts.get(columnIndex);
                    if (Objects.isNull(columnContext) || Objects.isNull(expressionContext)) {
                        continue;
                    }
                    currentStatement.addInsertValueMapping(columnContext.getStart(), columnContext.getStop(),
                            expressionContext.getStart(), expressionContext.getStop(), rowFirstToken, rowLastToken,
                            rowIndex, columnIndex);
                }
                for (int columnIndex = columnSize; columnIndex < columnContexts.size(); columnIndex++) {
                    MySqlParser.ColumnReferenceNameContext columnContext = columnContexts.get(columnIndex);
                    if (Objects.isNull(columnContext)) {
                        continue;
                    }
                    currentStatement.addUnmappedInsertColumn(columnContext.getStart(), columnContext.getStop(),
                            rowFirstToken, rowLastToken, rowIndex, columnIndex);
                }
                for (int valueIndex = columnSize; valueIndex < valueContexts.size(); valueIndex++) {
                    MySqlParser.ExpressionOrDefaultContext expressionContext = valueContexts.get(valueIndex);
                    if (Objects.isNull(expressionContext)) {
                        continue;
                    }
                    currentStatement.addUnmappedInsertValue(expressionContext.getStart(), expressionContext.getStop(),
                            rowFirstToken, rowLastToken, rowIndex, valueIndex);
                }
            }
        }).onFailure(e -> log.error("mysql visitInsertValueMappings error", e));
    }

    private static List<MySqlParser.ExpressionOrDefaultContext> expressionOrDefaultContexts(
            MySqlParser.InsertValueExpressionsContext rowContext) {
        if (Objects.isNull(rowContext)) {
            return List.of();
        }
        return rowContext.insertValueExpression().stream()
                .map(MySqlParser.InsertValueExpressionContext::expressionOrDefault)
                .filter(Objects::nonNull)
                .toList();
    }


    private void visitTableNameContext(MySqlParser.TableNameContext ctx) {
        Try.run(() -> {
            if (Objects.isNull(ctx)) {
                return;
            }
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return;
            }
            Token start = ctx.start;
            Token stop = ctx.stop;
            if (start.getTokenIndex() == stop.getTokenIndex()) {
                currentStatement.addIdentifier(MysqlStringUtil.removeQuote(start.getText()), IdentifierTypeEnum.TABLE.name(), start);
            } else {
                String databaseText = MysqlStringUtil.removeQuote(start.getText());
                currentStatement.addIdentifier(databaseText, IdentifierTypeEnum.DATABASE.name(), start);
                Identifier identifier = new Identifier();
                String tableText = stop.getText();
                if (tableText.trim().startsWith(".")) {
                    tableText = tableText.substring(1);
                }
                identifier.setIdentifierName(MysqlStringUtil.removeQuote(tableText));
                identifier.setIdentifierType(IdentifierTypeEnum.TABLE.name());
                identifier.setFirstToken(stop);
                identifier.setIdentifierDatabase(databaseText);
                currentStatement.addIdentifier(identifier);
            }
        }).onFailure(e -> log.error("mysql visitTableName error", e));
    }

    @Override
    public Void visitDeleteStatement(MySqlParser.DeleteStatementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DELETE.name());
        return super.visitDeleteStatement(ctx);
    }


    @Override
    public Void visitSingleDeleteStatement(MySqlParser.SingleDeleteStatementContext ctx) {
        MySqlParser.TableNameContext tableNameContext = ctx.tableReferenceName().tableName();
        visitTableNameContext(tableNameContext);
        return super.visitSingleDeleteStatement(ctx);
    }

    @Override
    public Void visitAlterTable(MySqlParser.AlterTableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_TABLE.name());
        MySqlParser.TableNameContext tableNameContext = ctx.tableReferenceName().tableName();
        visitTableNameContext(tableNameContext);
        return null;
    }
    @Override
    public Void visitCreateDatabase(MySqlParser.CreateDatabaseContext ctx) {
        context.setStatementType(SqlTypeEnum.CREATE_DATABASE);
        MySqlParser.UidContext uid = ctx.databaseDeclarationName().uid();
        visitUidContext(uid, IdentifierTypeEnum.DATABASE);
        return null;
    }

    private void visitUidContext(MySqlParser.UidContext uid, IdentifierTypeEnum identifierTypeEnum) {
        Try.run(() -> {
            if (Objects.nonNull(uid)) {
                Token start = uid.start;
                Statement currentStatement = context.getCurrentStatement();
                if (Objects.isNull(currentStatement)) {
                    return;
                }
                currentStatement.addIdentifier(MysqlStringUtil.removeQuote(start.getText()), identifierTypeEnum.name(), start);
            }
        }).onFailure(e -> log.error("mysql visitUidContext error", e));
    }

    @Override
    public Void visitCreateEvent(MySqlParser.CreateEventContext ctx) {
        context.setStatementType(SqlTypeEnum.CREATE_EVENT);
        MySqlParser.FullIdContext fullIdContext = ctx.eventDeclarationName().fullId();
        visitFullIdContext(fullIdContext, IdentifierTypeEnum.EVENT);
        return null;
    }

    @Override
    public Void visitCreateProcedure(MySqlParser.CreateProcedureContext ctx) {
        context.setStatementType(SqlTypeEnum.CREATE_PROCEDURE);
        MySqlParser.FullIdContext fullIdContext = ctx.procedureDeclarationName().fullId();
        visitFullIdContext(fullIdContext, IdentifierTypeEnum.PROCEDURE);
        return super.visitCreateProcedure(ctx);
    }

    @Override
    public Void visitCreateFunction(MySqlParser.CreateFunctionContext ctx) {
        context.setStatementType(SqlTypeEnum.CREATE_FUNCTION);
        MySqlParser.FullIdContext fullIdContext = ctx.functionDeclarationName().fullId();
        visitFullIdContext(fullIdContext, IdentifierTypeEnum.FUNCTION);
        return super.visitCreateFunction(ctx);
    }


    private void visitFullIdContext(MySqlParser.FullIdContext fullIdContext, IdentifierTypeEnum identifierTypeEnum) {
        Try.run(() -> {
            if (Objects.nonNull(fullIdContext)) {
                Token start = fullIdContext.start;
                Token stop = fullIdContext.stop;
                Statement currentStatement = context.getCurrentStatement();
                if (Objects.isNull(currentStatement)) {
                    return;
                }
                if (start.getTokenIndex() == stop.getTokenIndex()) {
                    currentStatement.addIdentifier(MysqlStringUtil.removeQuote(start.getText()), identifierTypeEnum.name(), start);
                } else {
                    String databaseText = MysqlStringUtil.removeQuote(start.getText());

                    currentStatement.addIdentifier(databaseText, IdentifierTypeEnum.DATABASE.name(), start);
                    Identifier identifier = new Identifier();
                    identifier.setIdentifierName(MysqlStringUtil.removeQuote(stop.getText().substring(1)));
                    identifier.setIdentifierType(identifierTypeEnum.name());
                    identifier.setFirstToken(stop);
                    identifier.setIdentifierDatabase(databaseText);

                    currentStatement.addIdentifier(identifier);
                }
            }
        }).onFailure(e -> log.error("mysql visitFullIdContext error", e));
    }

    @Override
    public Void visitCreateIndex(MySqlParser.CreateIndexContext ctx) {
        context.setStatementType(SqlTypeEnum.CREATE_INDEX);
        MySqlParser.UidContext uid = ctx.indexDeclarationName().uid();
        visitUidContext(uid, IdentifierTypeEnum.INDEX);
        MySqlParser.TableNameContext tableNameContext = ctx.tableReferenceName().tableName();
        visitTableNameContext(tableNameContext);
        return null;

    }


    @Override
    public Void visitCreateRole(MySqlParser.CreateRoleContext ctx) {
        context.setStatementType(SqlTypeEnum.CREATE_ROLE);
        for (MySqlParser.RoleDeclarationNameContext roleDeclarationNameContext : ctx.roleDeclarationName()) {
            Token start = roleDeclarationNameContext.start;
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            currentStatement.addIdentifier(MysqlStringUtil.removeQuote(start.getText()), IdentifierTypeEnum.ROLE.name(), start);
        }
        return null;
    }


    @Override
    public Void visitCreateTrigger(MySqlParser.CreateTriggerContext ctx) {
        context.setStatementType(SqlTypeEnum.CREATE_TRIGGER);
        MySqlParser.FullIdContext fullIdContext = ctx.triggerDeclarationName().fullId();
        visitFullIdContext(fullIdContext, IdentifierTypeEnum.TRIGGER);
        return super.visitCreateTrigger(ctx);

    }

    @Override
    public Void visitCreateView(MySqlParser.CreateViewContext ctx) {
        context.setStatementType(SqlTypeEnum.CREATE_VIEW);
        MySqlParser.FullIdContext fullIdContext = ctx.viewDeclarationName().tableName().fullId();
        visitFullIdContext(fullIdContext, IdentifierTypeEnum.VIEW);
        return super.visitCreateView(ctx);
    }

    @Override
    public Void visitColumnCreateTable(MySqlParser.ColumnCreateTableContext ctx) {
        context.setStatementType(SqlTypeEnum.CREATE_TABLE);
        MySqlParser.TableNameContext tableNameContext = ctx.tableDeclarationName().tableName();
        visitTableNameContext(tableNameContext);
        return super.visitColumnCreateTable(ctx);
    }


    @Override
    public Void visitUpdateStatement(MySqlParser.UpdateStatementContext ctx) {
        context.setStatementType(SqlTypeEnum.UPDATE);
        return super.visitUpdateStatement(ctx);
    }


    @Override
    public Void visitSingleUpdateStatement(MySqlParser.SingleUpdateStatementContext ctx) {
        MySqlParser.TableNameContext tableNameContext = ctx.tableReferenceName().tableName();
        visitTableNameContext(tableNameContext);
        return super.visitSingleUpdateStatement(ctx);
    }


    @Override
    public Void visitSimpleDescribeStatement(MySqlParser.SimpleDescribeStatementContext ctx) {
        Token command = ctx.command;
        if (command == null || (command.getType() != MySqlParser.DESC && command.getType() != MySqlParser.DESCRIBE)) {
            return super.visitSimpleDescribeStatement(ctx);
        }
        context.setStatementType(SqlTypeEnum.DESCRIBE);
        visitTableNameContext(ctx.tableReferenceName().tableName());
        return null;
    }


    @Override
    public Void visitUseStatement(MySqlParser.UseStatementContext ctx) {
        context.setStatementType(SqlTypeEnum.USE_DATABASE);
        MySqlParser.UidContext uid = ctx.databaseReferenceName().uid();
        visitUidContext(uid, IdentifierTypeEnum.DATABASE);
        return null;
    }

    @Override
    public Void visitUpdatedElement(MySqlParser.UpdatedElementContext ctx) {
        MySqlParser.FullColumnNameContext fullColumnNameContext = ctx.columnReferenceName().fullColumnName();
        visitFullColumnContext(fullColumnNameContext);
        return null;
    }

    @Override
    public Void visitFullColumnNameList(MySqlParser.FullColumnNameListContext ctx) {
        for (MySqlParser.FullColumnNameContext fullColumnNameContext : ctx.fullColumnName()) {
            visitFullColumnContext(fullColumnNameContext);
        }
        return super.visitFullColumnNameList(ctx);
    }

    @Override
    public Void visitColumnReferenceNameList(MySqlParser.ColumnReferenceNameListContext ctx) {
        for (MySqlParser.ColumnReferenceNameContext columnReferenceNameContext : ctx.columnReferenceName()) {
            visitFullColumnContext(columnReferenceNameContext.fullColumnName());
        }
        return super.visitColumnReferenceNameList(ctx);
    }

    @Override
    public Void visitDropDatabase(MySqlParser.DropDatabaseContext ctx) {
        context.setStatementType(SqlTypeEnum.DROP_DATABASE);
        MySqlParser.UidContext uid = ctx.databaseReferenceName().uid();
        visitUidContext(uid, IdentifierTypeEnum.DATABASE);
        return null;
    }


    @Override
    public Void visitFullColumnName(MySqlParser.FullColumnNameContext ctx) {
        MySqlParser.UidContext uid = ctx.uid();
        List<MySqlParser.DottedIdContext> dottedIdContexts = ctx.dottedId();
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        if (CollectionUtils.isEmpty(dottedIdContexts)) {
            visitUidContext(uid, IdentifierTypeEnum.COLUMN);
        } else {
            int size = dottedIdContexts.size();

            Token databaseToken = null, tableToken = null, columnToken = null;
            String databaseText = null, tableText = null, columnText = null;
            if (size == 2 && Objects.nonNull(uid)) {

                databaseToken = uid.getStart();
                tableToken = dottedIdContexts.get(0).getStart();
                columnToken = dottedIdContexts.get(1).getStart();
            } else if (size == 1 && Objects.nonNull(uid)) {
                tableToken = uid.getStart();
                columnToken = dottedIdContexts.get(0).getStart();
            }
            if (Objects.nonNull(databaseToken)) {
                String databaseTokenText = databaseToken.getText();
                if (databaseTokenText.startsWith(".")) {
                    databaseTokenText = databaseTokenText.substring(1);
                }
                databaseText = MysqlStringUtil.removeQuote(databaseTokenText);

                currentStatement.addIdentifier(databaseText, IdentifierTypeEnum.DATABASE.name(), databaseToken);
            }
            if (Objects.nonNull(tableToken)) {
                String tableTokenText = tableToken.getText();
                if (tableTokenText.startsWith(".")) {
                    tableTokenText = tableTokenText.substring(1);
                }
                tableText = MysqlStringUtil.removeQuote(tableTokenText);
                Identifier identifier = new Identifier();
                identifier.setIdentifierDatabase(databaseText);
                identifier.setIdentifierName(tableText);
                identifier.setIdentifierType(IdentifierTypeEnum.TABLE.name());
                identifier.setFirstToken(tableToken);

                currentStatement.addIdentifier(identifier);
            }
            if (Objects.nonNull(columnToken)) {
                String columnTokenText = columnToken.getText();
                if (columnTokenText.startsWith(".")) {
                    columnTokenText = columnTokenText.substring(1);
                }
                columnText = MysqlStringUtil.removeQuote(columnTokenText);
                Identifier identifier = new Identifier();
                identifier.setIdentifierDatabase(databaseText);
                identifier.setIdentifierTable(tableText);
                identifier.setIdentifierName(columnText);
                identifier.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
                identifier.setFirstToken(columnToken);
                currentStatement.addIdentifier(identifier);
            }
        }
        return super.visitFullColumnName(ctx);
    }

    @Override
    public Void visitDropIndex(MySqlParser.DropIndexContext ctx) {
        context.setStatementType(SqlTypeEnum.DROP_INDEX);
        MySqlParser.UidContext uid = ctx.indexReferenceName().uid();
        visitUidContext(uid, IdentifierTypeEnum.INDEX);
        MySqlParser.TableNameContext tableNameContext = ctx.tableReferenceName().tableName();
        visitTableNameContext(tableNameContext);
        return null;
    }


    @Override
    public Void visitDropProcedure(MySqlParser.DropProcedureContext ctx) {
        context.setStatementType(SqlTypeEnum.DROP_PROCEDURE);
        MySqlParser.FullIdContext fullIdContext = ctx.procedureReferenceName().fullId();
        visitFullIdContext(fullIdContext, IdentifierTypeEnum.PROCEDURE);
        return null;
    }

    @Override
    public Void visitDropFunction(MySqlParser.DropFunctionContext ctx) {
        context.setStatementType(SqlTypeEnum.DROP_FUNCTION);
        MySqlParser.FullIdContext fullIdContext = ctx.functionReferenceName().fullId();
        visitFullIdContext(fullIdContext, IdentifierTypeEnum.FUNCTION);
        return null;
    }


    @Override
    public Void visitDropTable(MySqlParser.DropTableContext ctx) {
        context.setStatementType(SqlTypeEnum.DROP_TABLE);
        MySqlParser.TablesContext tables = ctx.tables();
        visitTablesContext(tables);
        return null;
    }

    private void visitTablesContext(MySqlParser.TablesContext tables) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return;
        }
        if (Objects.nonNull(tables)) {
            for (MySqlParser.TableReferenceNameContext tableReferenceNameContext : tables.tableReferenceName()) {
                MySqlParser.TableNameContext tableNameContext = tableReferenceNameContext.tableName();
                Token start = tableNameContext.start;
                currentStatement.addIdentifier(MysqlStringUtil.removeQuote(start.getText()), IdentifierTypeEnum.TABLE.name(), start);
            }
        }
    }

    @Override
    public Void visitDropTrigger(MySqlParser.DropTriggerContext ctx) {
        context.setStatementType(SqlTypeEnum.DROP_TRIGGER);
        MySqlParser.FullIdContext fullIdContext = ctx.triggerReferenceName().fullId();
        visitFullIdContext(fullIdContext, IdentifierTypeEnum.TRIGGER);
        return null;
    }

    @Override
    public Void visitDropView(MySqlParser.DropViewContext ctx) {
        context.setStatementType(SqlTypeEnum.DROP_VIEW);
        for (MySqlParser.ViewReferenceNameContext viewReferenceNameContext : ctx.viewReferenceName()) {
            visitFullIdContext(viewReferenceNameContext.tableName().fullId(), IdentifierTypeEnum.VIEW);
        }
        return null;
    }

    @Override
    public Void visitDropRole(MySqlParser.DropRoleContext ctx) {
        context.setStatementType(SqlTypeEnum.DROP_ROLE);
        for (MySqlParser.RoleReferenceNameContext roleReferenceNameContext : ctx.roleReferenceName()) {
            Token start = roleReferenceNameContext.start;
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            currentStatement.addIdentifier(MysqlStringUtil.removeQuote(start.getText()), IdentifierTypeEnum.ROLE.name(), start);
        }
        return null;
    }

    @Override
    public Void visitDropUser(MySqlParser.DropUserContext ctx) {
        context.setStatementType(SqlTypeEnum.DROP_USER);
        for (MySqlParser.UserReferenceNameContext userReferenceNameContext : ctx.userReferenceName()) {
            Token start = userReferenceNameContext.start;
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            currentStatement.addIdentifier(MysqlStringUtil.removeQuote(start.getText()), IdentifierTypeEnum.USER.name(), start);
        }
        return null;
    }

    @Override
    public Void visitSelectColumnElement(MySqlParser.SelectColumnElementContext ctx) {
        Try.run(() -> {
            MySqlParser.FullColumnNameContext fullColumnNameContext = ctx.columnReferenceName().fullColumnName();
            MySqlParser.UidContext uid = ctx.aliasDeclarationName() == null ? null : ctx.aliasDeclarationName().uid();

            if (Objects.isNull(fullColumnNameContext) || Objects.isNull(uid)) {
                visitFullColumnContext(fullColumnNameContext);
                return;
            }
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return;
            }
            List<MySqlParser.DottedIdContext> dottedIdContexts = fullColumnNameContext.dottedId();
            MySqlParser.UidContext fullColumnNameUid = fullColumnNameContext.uid();
            int dottedIdSize = dottedIdContexts.size();
            String databaseText = null, tableText = null, columnText;
            Token databaseToken = null, tableToken = null, columnToken = null;

            if (dottedIdSize == 0) {
                columnToken = fullColumnNameUid.start;
            } else if (dottedIdSize == 2) {
                databaseToken = fullColumnNameUid.start;
                databaseText = MysqlStringUtil.removeQuote(databaseToken.getText());
                tableToken = dottedIdContexts.get(0).start;
                tableText = MysqlStringUtil.removeQuote(tableToken.getText().substring(1));
                columnToken = dottedIdContexts.get(1).start;
            } else if (dottedIdSize == 1) {
                tableToken = fullColumnNameUid.start;
                tableText = MysqlStringUtil.removeQuote(tableToken.getText());
                columnToken = dottedIdContexts.get(0).start;
            }
            if (Objects.nonNull(columnToken)) {
                columnText = columnToken.getText();
                if (columnText.trim().startsWith(".")) {
                    columnText = MysqlStringUtil.removeQuote(columnText.substring(1));
                }
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(columnText);
                identifier.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
                identifier.setFirstToken(columnToken);

                if (Objects.nonNull(databaseText)) {

                    currentStatement.addIdentifier(databaseText, IdentifierTypeEnum.DATABASE.name(), databaseToken);
                    identifier.setIdentifierDatabase(databaseText);
                }

                if (Objects.nonNull(tableText)) {

                    currentStatement.addIdentifier(tableText, IdentifierTypeEnum.TABLE.name(), tableToken);
                    identifier.setIdentifierTable(tableText);
                }
                Token aliasToken = uid.getStart();
                identifier.setIdentifierAlias(uid.getText());
                identifier.setLastToken(aliasToken);

                currentStatement.addIdentifier(identifier);
            }

        }).onFailure(e -> log.error("mysql visitSelectColumnElement error", e));
        return null;
    }


    private void visitFullColumnContext(MySqlParser.FullColumnNameContext fullColumnNameContext) {
        if (Objects.isNull(fullColumnNameContext)) {
            return;
        }
        Try.run(() -> {
            List<MySqlParser.DottedIdContext> dottedIdContexts = fullColumnNameContext.dottedId();
            MySqlParser.UidContext uid = fullColumnNameContext.uid();

            if (CollectionUtils.isEmpty(dottedIdContexts) || Objects.isNull(uid)) {
                visitUidContext(uid, IdentifierTypeEnum.COLUMN);
                return;
            }
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return;
            }
            int dottedIdSize = dottedIdContexts.size();
            String databaseText = null, tableText = null, columnText;
            Token databaseToken = null, tableToken = null, columnToken = null;

            if (dottedIdSize == 2) {
                databaseToken = uid.start;
                databaseText = MysqlStringUtil.removeQuote(databaseToken.getText());
                tableToken = dottedIdContexts.get(0).start;
                tableText = MysqlStringUtil.removeQuote(tableToken.getText().substring(1));
                columnToken = dottedIdContexts.get(1).start;
            } else if (dottedIdSize == 1) {
                tableToken = uid.start;
                tableText = MysqlStringUtil.removeQuote(tableToken.getText());
                columnToken = dottedIdContexts.get(0).start;
            }
            if (Objects.nonNull(columnToken)) {
                columnText = MysqlStringUtil.removeQuote(columnToken.getText().substring(1));
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(columnText);
                identifier.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
                identifier.setFirstToken(columnToken);

                if (Objects.nonNull(databaseText)) {
                    currentStatement.addIdentifier(databaseText, IdentifierTypeEnum.DATABASE.name(), databaseToken);
                    identifier.setIdentifierDatabase(databaseText);
                }

                if (Objects.nonNull(tableText)) {
                    currentStatement.addIdentifier(tableText, IdentifierTypeEnum.TABLE.name(), tableToken);
                    identifier.setIdentifierTable(tableText);
                }

                currentStatement.addIdentifier(identifier);
            }
        }).onFailure(e -> log.error("mysql visitFullColumnContext error", e));
    }

    @Override
    public Void visitAtomTableItem(MySqlParser.AtomTableItemContext ctx) {
        Try.run(() -> {
            MySqlParser.TableNameContext tableNameContext = ctx.tableReferenceName().tableName();
            MySqlParser.UidContext uid = ctx.aliasDeclarationName() == null ? null : ctx.aliasDeclarationName().uid();
            if (Objects.isNull(uid) || Objects.isNull(tableNameContext)) {
                visitTableNameContext(tableNameContext);
                return;
            }
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return;
            }
            Token start = tableNameContext.start;
            Token stop = tableNameContext.stop;
            Token uidStart = uid.getStart();
            String tableText = stop.getText();
            if (tableText.trim().startsWith(".")) {
                tableText = tableText.substring(1);
            }
            Identifier identifier = new Identifier();
            if (start.getTokenIndex() != stop.getTokenIndex()) {
                String databaseText = start.getText();
                currentStatement.addIdentifier(databaseText, IdentifierTypeEnum.DATABASE.name(), start);
                identifier.setIdentifierDatabase(databaseText);
                identifier.setIdentifierName(MysqlStringUtil.removeQuote(tableText));
            }
            identifier.setIdentifierName(MysqlStringUtil.removeQuote(tableText));
            identifier.setIdentifierType(IdentifierTypeEnum.TABLE.name());
            identifier.setFirstToken(stop);
            identifier.setIdentifierAlias(uid.getText());
            identifier.setLastToken(uidStart);
            currentStatement.addIdentifier(identifier);

        }).onFailure(e -> log.error("mysql visitAtomTableItem error", e));
        return null;
    }

    @Override
    public Void visitColumnDeclaration(MySqlParser.ColumnDeclarationContext ctx) {
        Try.of(() -> {
            MySqlParser.FullColumnNameContext fullColumnNameContext = ctx.columnDeclarationName().fullColumnName();
            if (Objects.isNull(fullColumnNameContext)) {
                return null;
            }
            String fullColumnName = context.getText(fullColumnNameContext.getSourceInterval());
            if (StringUtils.isBlank(fullColumnName)) {
                return null;
            }
            String[] split = fullColumnName.split("\\.");
            String columnName = split[split.length - 1];
            MySqlParser.ColumnDefinitionContext columnDefinitionContext = ctx.columnDefinition();
            MySqlParser.DataTypeContext dataTypeContext = columnDefinitionContext.dataType();
            if (Objects.isNull(dataTypeContext)) {
                return null;
            }
            String dataType = dataTypeContext.getText();
            String comment = null;
            for (MySqlParser.ColumnConstraintContext columnConstraintContext : columnDefinitionContext.columnConstraint()) {
                if (columnConstraintContext instanceof MySqlParser.CommentColumnConstraintContext commentConstraintContext) {
                    Token commentToken = commentConstraintContext.getStop();
                    if (Objects.nonNull(commentToken)) {
                        String text = commentToken.getText();
                        if (StringUtils.isNotBlank(text)) {
                            comment = text.substring(1, text.length() - 1);
                        }
                    }
                }
            }
            Identifier identifier = new Identifier();
            identifier.setIdentifierName(MysqlStringUtil.removeQuote(columnName));
            identifier.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
            identifier.setIdentifierDataType(dataType);
            identifier.setIdentifierComment(comment);
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            currentStatement.addIdentifier(identifier);
            return null;
        }).onFailure(e -> log.error("mysql visitColumnDeclaration error", e));

        return null;
    }


    @Override
    public Void visitFunctionArg(MySqlParser.FunctionArgContext ctx) {
        if (Objects.nonNull(ctx.columnReferenceName())) {
            visitFullColumnContext(ctx.columnReferenceName().fullColumnName());
        }
        return super.visitFunctionArg(ctx);
    }

    @Override
    public Void visitUdfFunctionCall(MySqlParser.UdfFunctionCallContext ctx) {
        MySqlParser.FullIdContext fullIdContext = ctx.fullId();
        visitFullIdContext(fullIdContext, IdentifierTypeEnum.UDF_FUNCTION);
        return null;
    }

    @Override
    public Void visitAggregateFunctionCall(MySqlParser.AggregateFunctionCallContext ctx) {
        Token start = ctx.getStart();
        if (Objects.nonNull(start)) {
            Identifier identifier = new Identifier();
            identifier.setIdentifierName(start.getText());
            identifier.setIdentifierType(IdentifierTypeEnum.SYS_FUNCTION.name());
            identifier.setFirstToken(start);
            Statement currentStatement = context.getCurrentStatement();
            if (Objects.isNull(currentStatement)) {
                return null;
            }
            currentStatement.addIdentifier(identifier);
        }
        return null;
    }

    @Override
    public Void visitCallStatement(MySqlParser.CallStatementContext ctx) {
        MySqlParser.FullIdContext fullIdContext = ctx.procedureReferenceName().fullId();
        if (Objects.nonNull(fullIdContext)) {
            visitFullIdContext(fullIdContext, IdentifierTypeEnum.PROCEDURE);
        }
        return null;
    }
}
