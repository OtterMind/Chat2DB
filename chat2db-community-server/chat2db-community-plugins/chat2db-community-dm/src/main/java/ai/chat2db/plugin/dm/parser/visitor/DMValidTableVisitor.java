package ai.chat2db.plugin.dm.parser.visitor;

import ai.chat2db.community.domain.api.enums.parser.IdentifierTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.StatementValidTypeEnum;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.parser.statement.StatementContext;
import ai.chat2db.community.domain.api.model.parser.token.Identifier;
import ai.chat2db.plugin.dm.parser.base.DMParser;
import ai.chat2db.plugin.dm.parser.base.DMParserBaseVisitor;
import ai.chat2db.plugin.dm.parser.listener.DMSelectListener;
import ai.chat2db.spi.util.SqlStringUtil;
import ai.chat2db.spi.util.TokenUtil;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

import java.util.List;
import java.util.Objects;

@Slf4j
public class DMValidTableVisitor extends DMParserBaseVisitor<Void> {

    private final StatementContext context;

    private final DMSelectListener oracleSelectListener;

    public DMValidTableVisitor(StatementContext context) {
        this.context = context;
        oracleSelectListener = new DMSelectListener(context);
    }


    @Override
    public Void visitSql_script(DMParser.Sql_scriptContext ctx) {

        List<DMParser.Unit_statementContext> unitStatement = ctx.unit_statement();
        TokenStream commonTokenStream = context.getCommonTokenStream();
        for (DMParser.Unit_statementContext child : unitStatement) {
            Statement statement = new Statement();
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
    public Void visitTruncate_table(DMParser.Truncate_tableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.TRUNCATE_TABLE.name());
        return null;
    }

    @Override
    public Void visitDml_table_expression_clause(DMParser.Dml_table_expression_clauseContext ctx) {
        DMParser.Tableview_nameContext tableviewNameContext = ctx.tableview_name();
        visitTableViewName(tableviewNameContext, IdentifierTypeEnum.TABLE);
        return super.visitDml_table_expression_clause(ctx);
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
    public Void visitCreate_table(DMParser.Create_tableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_TABLE.name());
        return null;
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
    public Void visitTable_ref_aux(DMParser.Table_ref_auxContext ctx) {
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
    public Void visitAlter_view(DMParser.Alter_viewContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_VIEW.name());
        return super.visitAlter_view(ctx);
    }

    @Override
    public Void visitAlter_analytic_view(DMParser.Alter_analytic_viewContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_ANALYTIC_VIEW.name());
        return super.visitAlter_analytic_view(ctx);
    }

    @Override
    public Void visitAlter_attribute_dimension(DMParser.Alter_attribute_dimensionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_ATTRIBUTE_DIMENSION.name());
        return super.visitAlter_attribute_dimension(ctx);
    }


    @Override
    public Void visitAlter_audit_policy(DMParser.Alter_audit_policyContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_AUDIT_POLICY.name());
        return super.visitAlter_audit_policy(ctx);
    }

    @Override
    public Void visitAlter_cluster(DMParser.Alter_clusterContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_CLUSTER.name());
        return super.visitAlter_cluster(ctx);
    }


    @Override
    public Void visitAlter_database(DMParser.Alter_databaseContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_DATABASE.name());
        return super.visitAlter_database(ctx);
    }


    @Override
    public Void visitAlter_database_link(DMParser.Alter_database_linkContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_DATABASE_LINK.name());
        return super.visitAlter_database_link(ctx);
    }

    @Override
    public Void visitAlter_dimension(DMParser.Alter_dimensionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_DIMENSION.name());
        return super.visitAlter_dimension(ctx);
    }


    @Override
    public Void visitAlter_diskgroup(DMParser.Alter_diskgroupContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_DISKGROUP.name());
        return super.visitAlter_diskgroup(ctx);
    }

    @Override
    public Void visitAlter_flashback_archive(DMParser.Alter_flashback_archiveContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_FLASHBACK_ARCHIVE.name());
        return super.visitAlter_flashback_archive(ctx);
    }

    @Override
    public Void visitAlter_function(DMParser.Alter_functionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_FUNCTION.name());
        return super.visitAlter_function(ctx);
    }

    @Override
    public Void visitAlter_hierarchy(DMParser.Alter_hierarchyContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_HIERARCHY.name());
        return super.visitAlter_hierarchy(ctx);
    }

    @Override
    public Void visitAlter_index(DMParser.Alter_indexContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_INDEX.name());
        return super.visitAlter_index(ctx);
    }


    @Override
    public Void visitAlter_inmemory_join_group(DMParser.Alter_inmemory_join_groupContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_INMEMORY_JOIN_GROUP.name());
        return super.visitAlter_inmemory_join_group(ctx);
    }

    @Override
    public Void visitAlter_java(DMParser.Alter_javaContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_JAVA.name());
        return super.visitAlter_java(ctx);
    }

    @Override
    public Void visitAlter_library(DMParser.Alter_libraryContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_LIBRARY.name());
        return super.visitAlter_library(ctx);
    }

    @Override
    public Void visitAlter_lockdown_profile(DMParser.Alter_lockdown_profileContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_LOCKDOWN_PROFILE.name());
        return super.visitAlter_lockdown_profile(ctx);
    }

    @Override
    public Void visitAlter_materialized_view(DMParser.Alter_materialized_viewContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_MATERIALIZED_VIEW.name());
        return super.visitAlter_materialized_view(ctx);
    }

    @Override
    public Void visitAlter_materialized_view_log(DMParser.Alter_materialized_view_logContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_MATERIALIZED_VIEW_LOG.name());
        return super.visitAlter_materialized_view_log(ctx);
    }

    @Override
    public Void visitAlter_materialized_zonemap(DMParser.Alter_materialized_zonemapContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_MATERIALIZED_ZONEMAP.name());
        return super.visitAlter_materialized_zonemap(ctx);
    }

    @Override
    public Void visitAlter_operator(DMParser.Alter_operatorContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_OPERATOR.name());
        return super.visitAlter_operator(ctx);
    }

    @Override
    public Void visitAlter_outline(DMParser.Alter_outlineContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_OUTLINE.name());
        return super.visitAlter_outline(ctx);
    }

    @Override
    public Void visitAlter_package(DMParser.Alter_packageContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_PACKAGE.name());
        return super.visitAlter_package(ctx);
    }


    @Override
    public Void visitAlter_pmem_filestore(DMParser.Alter_pmem_filestoreContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_PMEM_FILESTORE.name());
        return super.visitAlter_pmem_filestore(ctx);
    }

    @Override
    public Void visitAlter_procedure(DMParser.Alter_procedureContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_PROCEDURE.name());
        return super.visitAlter_procedure(ctx);
    }


    @Override
    public Void visitAlter_resource_cost(DMParser.Alter_resource_costContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_RESOURCE_COST.name());
        return super.visitAlter_resource_cost(ctx);
    }

    @Override
    public Void visitAlter_role(DMParser.Alter_roleContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_ROLE.name());
        return super.visitAlter_role(ctx);
    }

    @Override
    public Void visitAlter_rollback_segment(DMParser.Alter_rollback_segmentContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_ROLLBACK_SEGMENT.name());
        return super.visitAlter_rollback_segment(ctx);
    }

    @Override
    public Void visitAlter_sequence(DMParser.Alter_sequenceContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_SEQUENCE.name());
        return super.visitAlter_sequence(ctx);
    }

    @Override
    public Void visitAlter_session(DMParser.Alter_sessionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_SESSION.name());
        return super.visitAlter_session(ctx);
    }

    @Override
    public Void visitAlter_synonym(DMParser.Alter_synonymContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_SYNONYM.name());
        return super.visitAlter_synonym(ctx);
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
    public Void visitAlter_tablespace(DMParser.Alter_tablespaceContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_TABLESPACE.name());
        return super.visitAlter_tablespace(ctx);
    }

    @Override
    public Void visitAlter_tablespace_set(DMParser.Alter_tablespace_setContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_TABLESPACE_SET.name());
        return super.visitAlter_tablespace_set(ctx);
    }

    @Override
    public Void visitAlter_trigger(DMParser.Alter_triggerContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_TRIGGER.name());
        return super.visitAlter_trigger(ctx);
    }

    @Override
    public Void visitAlter_type(DMParser.Alter_typeContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_TYPE.name());
        return super.visitAlter_type(ctx);
    }

    @Override
    public Void visitAlter_user(DMParser.Alter_userContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.ALTER_USER.name());
        return super.visitAlter_user(ctx);
    }


    @Override
    public Void visitCall_statement(DMParser.Call_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CALL_PROC.name());
        return super.visitCall_statement(ctx);
    }

    @Override
    public Void visitCreate_analytic_view(DMParser.Create_analytic_viewContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_ANALYTIC_VIEW.name());
        return super.visitCreate_analytic_view(ctx);
    }

    @Override
    public Void visitCreate_attribute_dimension(DMParser.Create_attribute_dimensionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_ATTRIBUTE_DIMENSION.name());
        return super.visitCreate_attribute_dimension(ctx);
    }

    @Override
    public Void visitCreate_audit_policy(DMParser.Create_audit_policyContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_AUDIT_POLICY.name());
        return super.visitCreate_audit_policy(ctx);
    }

    @Override
    public Void visitCreate_cluster(DMParser.Create_clusterContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_CLUSTER.name());
        return super.visitCreate_cluster(ctx);
    }

    @Override
    public Void visitCreate_context(DMParser.Create_contextContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_CONTEXT.name());
        return super.visitCreate_context(ctx);
    }

    @Override
    public Void visitCreate_controlfile(DMParser.Create_controlfileContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_CONTROLFILE.name());
        return super.visitCreate_controlfile(ctx);
    }

    @Override
    public Void visitCreate_diskgroup(DMParser.Create_diskgroupContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_DISKGROUP.name());
        return super.visitCreate_diskgroup(ctx);
    }

    @Override
    public Void visitCreate_edition(DMParser.Create_editionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_EDITION.name());
        return super.visitCreate_edition(ctx);
    }

    @Override
    public Void visitCreate_flashback_archive(DMParser.Create_flashback_archiveContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_FLASHBACK_ARCHIVE.name());
        return super.visitCreate_flashback_archive(ctx);
    }

    @Override
    public Void visitCreate_hierarchy(DMParser.Create_hierarchyContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_HIERARCHY.name());
        return super.visitCreate_hierarchy(ctx);
    }

    @Override
    public Void visitCreate_inmemory_join_group(DMParser.Create_inmemory_join_groupContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_INMEMORY_JOIN_GROUP.name());
        return super.visitCreate_inmemory_join_group(ctx);
    }

    @Override
    public Void visitCreate_java(DMParser.Create_javaContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_JAVA.name());
        return super.visitCreate_java(ctx);
    }

    @Override
    public Void visitCreate_library(DMParser.Create_libraryContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_LIBRARY.name());
        return super.visitCreate_library(ctx);
    }

    @Override
    public Void visitCreate_lockdown_profile(DMParser.Create_lockdown_profileContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_LOCKDOWN_PROFILE.name());
        return super.visitCreate_lockdown_profile(ctx);
    }

    @Override
    public Void visitCreate_operator(DMParser.Create_operatorContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_OPERATOR.name());
        return super.visitCreate_operator(ctx);
    }

    @Override
    public Void visitCreate_outline(DMParser.Create_outlineContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_OUTLINE.name());
        return super.visitCreate_outline(ctx);
    }

    @Override
    public Void visitCreate_restore_point(DMParser.Create_restore_pointContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_RESTORE_POINT.name());
        return super.visitCreate_restore_point(ctx);
    }

    @Override
    public Void visitCreate_spfile(DMParser.Create_spfileContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.CREATE_SPFILE.name());
        return super.visitCreate_spfile(ctx);
    }

    @Override
    public Void visitDrop_analytic_view(DMParser.Drop_analytic_viewContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_ANALYTIC_VIEW.name());
        return super.visitDrop_analytic_view(ctx);
    }

    @Override
    public Void visitDrop_attribute_dimension(DMParser.Drop_attribute_dimensionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_ATTRIBUTE_DIMENSION.name());
        return super.visitDrop_attribute_dimension(ctx);
    }

    @Override
    public Void visitDrop_audit_policy(DMParser.Drop_audit_policyContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_AUDIT_POLICY.name());
        return super.visitDrop_audit_policy(ctx);
    }

    @Override
    public Void visitDrop_cluster(DMParser.Drop_clusterContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_CLUSTER.name());
        return super.visitDrop_cluster(ctx);
    }

    @Override
    public Void visitDrop_context(DMParser.Drop_contextContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_CONTEXT.name());
        return super.visitDrop_context(ctx);
    }

    @Override
    public Void visitDrop_directory(DMParser.Drop_directoryContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_DIRECTORY.name());
        return super.visitDrop_directory(ctx);
    }

    @Override
    public Void visitDrop_diskgroup(DMParser.Drop_diskgroupContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_DISKGROUP.name());
        return super.visitDrop_diskgroup(ctx);
    }

    @Override
    public Void visitDrop_edition(DMParser.Drop_editionContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_EDITION.name());
        return super.visitDrop_edition(ctx);
    }

    @Override
    public Void visitDrop_flashback_archive(DMParser.Drop_flashback_archiveContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_FLASHBACK_ARCHIVE.name());
        return super.visitDrop_flashback_archive(ctx);
    }

    @Override
    public Void visitDrop_hierarchy(DMParser.Drop_hierarchyContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_HIERARCHY.name());
        return super.visitDrop_hierarchy(ctx);
    }

    @Override
    public Void visitDrop_inmemory_join_group(DMParser.Drop_inmemory_join_groupContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_INMEMORY_JOIN_GROUP.name());
        return super.visitDrop_inmemory_join_group(ctx);
    }

    @Override
    public Void visitDrop_java(DMParser.Drop_javaContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_JAVA.name());
        return super.visitDrop_java(ctx);
    }

    @Override
    public Void visitDrop_library(DMParser.Drop_libraryContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_LIBRARY.name());
        return super.visitDrop_library(ctx);
    }

    @Override
    public Void visitDrop_lockdown_profile(DMParser.Drop_lockdown_profileContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_LOCKDOWN_PROFILE.name());
        return super.visitDrop_lockdown_profile(ctx);
    }

    @Override
    public Void visitDrop_operator(DMParser.Drop_operatorContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_OPERATOR.name());
        return super.visitDrop_operator(ctx);
    }

    @Override
    public Void visitDrop_outline(DMParser.Drop_outlineContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_OUTLINE.name());
        return super.visitDrop_outline(ctx);
    }

    @Override
    public Void visitDrop_restore_point(DMParser.Drop_restore_pointContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DROP_RESTORE_POINT.name());
        return super.visitDrop_restore_point(ctx);
    }

    @Override
    public Void visitComment_on_column(DMParser.Comment_on_columnContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.COMMENT_ON_COLUMN.name());
        return super.visitComment_on_column(ctx);
    }

    @Override
    public Void visitComment_on_table(DMParser.Comment_on_tableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.COMMENT_ON_TABLE.name());
        return super.visitComment_on_table(ctx);
    }

    @Override
    public Void visitComment_on_materialized(DMParser.Comment_on_materializedContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.COMMENT_ON_MATERIALIZED.name());
        return super.visitComment_on_materialized(ctx);
    }

    @Override
    public Void visitDisassociate_statistics(DMParser.Disassociate_statisticsContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.DISASSOCIATE_STATISTICS.name());
        return super.visitDisassociate_statistics(ctx);
    }

    @Override
    public Void visitFlashback_table(DMParser.Flashback_tableContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.FLASHBACK_TABLE.name());
        return super.visitFlashback_table(ctx);
    }

    @Override
    public Void visitGrant_statement(DMParser.Grant_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.GRANT.name());
        return super.visitGrant_statement(ctx);
    }

    @Override
    public Void visitNoaudit_statement(DMParser.Noaudit_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.NOAUDIT.name());
        return super.visitNoaudit_statement(ctx);
    }

    @Override
    public Void visitPurge_statement(DMParser.Purge_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.PURGE.name());
        return super.visitPurge_statement(ctx);
    }

    @Override
    public Void visitRename_object(DMParser.Rename_objectContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.RENAME.name());
        return super.visitRename_object(ctx);
    }

    @Override
    public Void visitRevoke_statement(DMParser.Revoke_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.REVOKE.name());
        return super.visitRevoke_statement(ctx);
    }

    @Override
    public Void visitTruncate_cluster(DMParser.Truncate_clusterContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.TRUNCATE_CLUSTER.name());
        return super.visitTruncate_cluster(ctx);
    }

    @Override
    public Void visitUnified_auditing(DMParser.Unified_auditingContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.UNIFIED_AUDIT.name());
        return super.visitUnified_auditing(ctx);
    }

    @Override
    public Void visitSelect_statement(DMParser.Select_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.SELECT.name());
        oracleSelectListener.parserSelectStatement(ctx);
        return null;
    }

    @Override
    public Void visitInsert_statement(DMParser.Insert_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.INSERT.name());
        return super.visitInsert_statement(ctx);
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
    public Void visitUpdate_statement(DMParser.Update_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.UPDATE.name());
        return super.visitUpdate_statement(ctx);
    }

    @Override
    public Void visitMerge_statement(DMParser.Merge_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.MERGE.name());
        return super.visitMerge_statement(ctx);
    }

    @Override
    public Void visitLock_table_statement(DMParser.Lock_table_statementContext ctx) {
        Statement currentStatement = context.getCurrentStatement();
        if (Objects.isNull(currentStatement)) {
            return null;
        }
        currentStatement.setType(SqlTypeEnum.LOCK_TABLES.name());
        return super.visitLock_table_statement(ctx);
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
}
