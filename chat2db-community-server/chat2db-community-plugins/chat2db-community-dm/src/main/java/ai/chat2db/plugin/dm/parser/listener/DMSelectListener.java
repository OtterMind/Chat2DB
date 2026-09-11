package ai.chat2db.plugin.dm.parser.listener;

import ai.chat2db.community.domain.api.enums.parser.IdentifierTypeEnum;
import ai.chat2db.community.domain.api.model.parser.info.ColumnInfo;
import ai.chat2db.community.domain.api.model.parser.info.TableInfo;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.StatementContext;
import ai.chat2db.community.domain.api.model.parser.token.Identifier;
import ai.chat2db.plugin.dm.parser.base.DMParser;
import ai.chat2db.plugin.dm.parser.base.DMParserBaseListener;
import ai.chat2db.spi.util.SqlStringUtil;
import ai.chat2db.spi.util.TokenUtil;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.*;

@Slf4j
public class DMSelectListener extends DMParserBaseListener {


    private final StatementContext context;

    private Map<TableInfo, String> tableAliasMap = new HashMap<>();

    private Map<ColumnInfo, String> columnAliasMap = new HashMap<>();

    public DMSelectListener(StatementContext context) {
        this.context = context;
    }

    public void parserSelectStatement(ParseTree parseTree) {
        tableAliasMap = new HashMap<>();
        columnAliasMap = new HashMap<>();
        ParseTreeWalker parseTreeWalker = new ParseTreeWalker();
        parseTreeWalker.walk(this, parseTree);
    }


    @Override
    public void exitSelect_statement(DMParser.Select_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        currentStatement.setColumnAliasMap(columnAliasMap);
        currentStatement.setTableAliasMap(tableAliasMap);
    }

    @Override
    public void enterSelected_list(DMParser.Selected_listContext ctx) {
        TerminalNode asterisk = ctx.ASTERISK();
        if (Objects.nonNull(asterisk)) {
            columnAliasMap.put(
                    ColumnInfo.builder().column("*").build(), null
            );
        }
    }

    @Override
    public void enterSelect_list_elements(DMParser.Select_list_elementsContext ctx) {
        TerminalNode asterisk = ctx.ASTERISK();
        if (asterisk != null) {
            String schema = null, table = null;
            DMParser.Tableview_nameContext tableviewNameContext = ctx.tableview_name();
            if (tableviewNameContext != null) {
                String text = tableviewNameContext.getText();
                String[] split = text.split("\\.");
                int length = split.length;
                if (length == 2) {
                    schema = SqlStringUtil.removeQuote(split[0]);
                }
                table = SqlStringUtil.removeQuote(split[length - 1]);
            }
            columnAliasMap.put(
                    ColumnInfo.builder().schema(schema).table(table).column("*").build(), null
            );
            return;
        }
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
            ColumnInfo columnInfo;
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

            if (Objects.nonNull(column)) {
                Identifier identifier = new Identifier();
                identifier.setIdentifierName(columnText);
                identifier.setIdentifierType(IdentifierTypeEnum.COLUMN.name());
                identifier.setFirstToken(column);
                identifier.setIdentifierSchema(schemaText);
                identifier.setIdentifierTable(tableText);
                columnInfo = ColumnInfo.builder().schema(schemaText).table(tableText).column(columnText).build();
                if (Objects.nonNull(alias)) {
                    aliasText = alias.getText();
                }
                columnAliasMap.put(columnInfo, aliasText);
            }
            return null;

        }).onFailure(e -> {
            log.error("enter select list error", e);
        });

    }


    @Override
    public void enterTable_ref_aux(DMParser.Table_ref_auxContext ctx) {
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
            TableInfo tableInfo;
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
            tableInfo = TableInfo.builder().schema(schemaText).table(tableText).build();
            if (Objects.nonNull(alias)) {
                aliasText = alias.getText();
            }
            tableAliasMap.put(tableInfo, aliasText);
            return null;
        }).onFailure(e -> log.error("enter table ref aux error", e));
    }


}
