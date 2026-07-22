package ai.chat2db.plugin.mysql.completion.plan;

import ai.chat2db.community.domain.api.model.completion.core.SqlCompletionCandidates;
import ai.chat2db.plugin.mysql.completion.analysis.statement.ddl.trigger.MysqlCreateTriggerStatementPseudoRecordAnalyzer;
import ai.chat2db.plugin.mysql.config.completion.MysqlSqlCompletionFunctionTokenConfig;
import ai.chat2db.plugin.mysql.completion.provider.callable.MysqlSqlCompletionCallableCandidateSupport;
import ai.chat2db.plugin.mysql.completion.provider.charset.MysqlSqlCompletionCharsetCandidateProvider;
import ai.chat2db.plugin.mysql.completion.provider.collation.MysqlSqlCompletionCollationCandidateProvider;
import ai.chat2db.plugin.mysql.completion.provider.column.MysqlSqlCompletionColumnReferenceCandidateProvider;
import ai.chat2db.plugin.mysql.completion.provider.datatype.MysqlSqlCompletionDataTypeCandidateProvider;
import ai.chat2db.plugin.mysql.completion.provider.object.MysqlSqlCompletionObjectCandidateProvider;
import ai.chat2db.plugin.mysql.completion.provider.routine.MysqlSqlCompletionRoutineLocalSymbolCandidateProvider;
import ai.chat2db.plugin.mysql.completion.provider.table.MysqlSqlCompletionTableCandidateProvider;
import ai.chat2db.plugin.mysql.completion.provider.function.MysqlSqlCompletionFunctionCandidateProvider;
import ai.chat2db.plugin.mysql.completion.provider.snippet.MysqlSqlCompletionSnippetCandidateProvider;
import ai.chat2db.plugin.mysql.completion.provider.syntax.MysqlSqlCompletionSyntaxCandidateCollector;
import ai.chat2db.plugin.mysql.completion.presentation.MysqlSqlCompletionCandidatePostProcessor;
import ai.chat2db.plugin.mysql.enums.completion.MysqlSqlCompletionRuleSlotTypeEnum;
import ai.chat2db.plugin.mysql.model.completion.context.MysqlSqlCompletionCandidateContext;
import ai.chat2db.plugin.mysql.completion.slot.MysqlSqlCompletionRuleSlot;
import ai.chat2db.mysql.parser.base.MySqlParser;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionResponse;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionIntentTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionKeywordCaseEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.plan.SqlCompletionCandidatePlan;
import ai.chat2db.community.domain.api.model.completion.plan.SqlCompletionCandidatePlanItem;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;


public final class MysqlSqlCompletionCandidatePlanExecutor {

    private static final List<CandidateBuildHandler> BUILD_HANDLERS = List.of(
            CandidateBuildHandler.of(MysqlSqlCompletionRuleSlot::objectReference,
                    MysqlSqlCompletionCandidatePlanExecutor::buildObjectReferencePlan),
            CandidateBuildHandler.of(MysqlSqlCompletionRuleSlot::tableReference,
                    MysqlSqlCompletionCandidatePlanExecutor::buildTableReferencePlan),
            CandidateBuildHandler.of(MysqlSqlCompletionRuleSlot::tableDeclaration,
                    MysqlSqlCompletionCandidatePlanExecutor::buildTableDeclarationPlan),
            CandidateBuildHandler.of(MysqlSqlCompletionRuleSlot::charsetReference,
                    MysqlSqlCompletionCandidatePlanExecutor::buildCharsetReferencePlan),
            CandidateBuildHandler.of(MysqlSqlCompletionRuleSlot::collationReference,
                    MysqlSqlCompletionCandidatePlanExecutor::buildCollationReferencePlan),
            CandidateBuildHandler.of(MysqlSqlCompletionRuleSlot::dataTypeReference,
                    MysqlSqlCompletionCandidatePlanExecutor::buildDataTypeReferencePlan),
            CandidateBuildHandler.of(MysqlSqlCompletionRuleSlot::insertValueExpression,
                    MysqlSqlCompletionCandidatePlanExecutor::buildInsertValueExpressionPlan),
            CandidateBuildHandler.of(MysqlSqlCompletionRuleSlot::columnReference,
                    MysqlSqlCompletionCandidatePlanExecutor::buildColumnReferencePlan));

    private MysqlSqlCompletionCandidatePlanExecutor() {
    }

    public static SqlCompletionResponse execute(MysqlSqlCompletionCandidateContext context,
                                              SqlCompletionCandidatePlan candidatePlan) {
        if (context == null || context.cursorContext() == null || !context.cursorContext().admitted()) {
            return SqlCompletionResponse.empty();
        }
        SqlCompletionCandidates c3Result = context.c3Result();
        MysqlSqlCompletionRuleSlot ruleSlot = context.ruleSlot();
        if (isBlockedBlankPrefix(context, ruleSlot)) {
            return SqlCompletionResponse.empty();
        }
        if (MysqlSqlCompletionContextGuards.invalidFixedAggregateExtraArgument(context)) {
            return SqlCompletionResponse.empty();
        }
        boolean blankPrefix = isBlankPrefix(context);
        List<SqlCompletionCandidate> keywordCandidates = keywordCandidates(context, ruleSlot, c3Result);
        MysqlSqlCompletionCandidateBuildPlan buildPlan = buildCandidates(context, ruleSlot, c3Result, candidatePlan);
        if (buildPlan.unsupported()) {
            return SqlCompletionResponse.unsupported(context.request().databaseType());
        }
        boolean aliasMatched = hasAliasCandidate(buildPlan);
        List<SqlCompletionCandidate> candidates = buildPlan.candidates();
        if (!aliasMatched) {
            if (!blankPrefix) {
                candidates.addAll(snippetCandidates(context, ruleSlot, c3Result));
            }
            candidates.addAll(keywordCandidates);
            candidates.addAll(builtinFunctionCandidates(context, c3Result));
        }
        candidates.addAll(blankPrefixRelationAliases(context, ruleSlot, blankPrefix));
        List<SqlCompletionCandidate> processed = MysqlSqlCompletionCandidatePostProcessor.process(candidates,
                SqlCompletionKeywordCaseEnum.from(context.request().keywordCase()));
        if (processed.isEmpty()) {
            return SqlCompletionResponse.empty();
        }
        return SqlCompletionResponse.success(context.replaceStart(), context.replaceEnd(), processed);
    }

    public static SqlCompletionResponse execute(MysqlSqlCompletionCandidateContext context) {
        return execute(context, SqlCompletionCandidatePlan.empty());
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildCandidates(MysqlSqlCompletionCandidateContext context,
                                                                        MysqlSqlCompletionRuleSlot ruleSlot,
                                                                        SqlCompletionCandidates c3Result,
                                                                        SqlCompletionCandidatePlan candidatePlan) {
        if (candidatePlan == null || candidatePlan.items().isEmpty()) {
            return buildCandidatesBySlot(context, ruleSlot, c3Result);
        }
        List<SqlCompletionCandidate> candidates = new ArrayList<>();
        if (MysqlCreateTriggerStatementPseudoRecordAnalyzer.resolvePseudoRecordTargetRelationScope(context)
                .isPresent()) {
            MysqlSqlCompletionCandidateBuildPlan buildPlan = buildColumnReferencePlan(context, ruleSlot, c3Result);
            if (buildPlan.unsupported()) {
                return buildPlan;
            }
            candidates.addAll(buildPlan.candidates());
            return buildPlan(candidates);
        }
        if (hasIntent(candidatePlan, SqlCompletionIntentTypeEnum.LOCAL_SYMBOLS)) {
            MysqlSqlCompletionCandidateBuildPlan localSymbols = buildRoutineLocalSymbols(context, ruleSlot);
            candidates.addAll(localSymbols.candidates());
        }
        for (SqlCompletionCandidatePlanItem item : candidatePlan.items()) {
            MysqlSqlCompletionCandidateBuildPlan buildPlan = buildPlanItem(context, ruleSlot, c3Result, item);
            if (buildPlan == null) {
                continue;
            }
            if (buildPlan.unsupported()) {
                return fallbackToCollectedCandidates(candidates, buildPlan);
            }
            candidates.addAll(buildPlan.candidates());
            return buildPlan(candidates);
        }
        if (!candidates.isEmpty()) {
            return buildPlan(candidates);
        }
        return buildCandidatesBySlot(context, ruleSlot, c3Result);
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildCandidatesBySlot(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        List<SqlCompletionCandidate> candidates = new ArrayList<>();
        MysqlSqlCompletionCandidateBuildPlan localSymbols = buildRoutineLocalSymbols(context, ruleSlot);
        candidates.addAll(localSymbols.candidates());
        for (CandidateBuildHandler handler : BUILD_HANDLERS) {
            if (handler.matches(ruleSlot)) {
                MysqlSqlCompletionCandidateBuildPlan buildPlan = handler.build(context, ruleSlot, c3Result);
                if (buildPlan.unsupported()) {
                    return fallbackToCollectedCandidates(candidates, buildPlan);
                }
                candidates.addAll(buildPlan.candidates());
                return buildPlan(candidates);
            }
        }
        if (qualifiedDotColumnScope(context, ruleSlot)) {
            MysqlSqlCompletionCandidateBuildPlan buildPlan = buildColumnReferencePlan(context, ruleSlot, c3Result);
            if (buildPlan.unsupported()) {
                return buildPlan;
            }
            candidates.addAll(buildPlan.candidates());
            return buildPlan(candidates);
        }
        MysqlSqlCompletionCandidateBuildResult dataTypes = buildDataTypesFromC3(context, c3Result);
        candidates.addAll(dataTypes.candidates());
        return buildPlan(candidates);
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildPlanItem(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result,
            SqlCompletionCandidatePlanItem item) {
        if (item == null || item.intent() == null || item.intent().type() == null) {
            return null;
        }
        return switch (SqlCompletionIntentTypeEnum.from(item.intent().type())) {
            case TABLES -> ruleSlot != null && ruleSlot.tableReference()
                    ? buildTableReferencePlan(context, ruleSlot, c3Result) : null;
            case TABLE_QUALIFIERS -> ruleSlot != null && ruleSlot.tableDeclaration()
                    ? buildTableDeclarationPlan(context, ruleSlot, c3Result) : null;
            case COLUMNS -> buildColumnLikePlan(context, ruleSlot, c3Result);
            case OBJECTS -> ruleSlot != null && ruleSlot.objectReference()
                    ? buildObjectReferencePlan(context, ruleSlot, c3Result) : null;
            case DATA_TYPES -> buildDataTypeLikePlan(context, ruleSlot, c3Result);
            case CHARSETS -> ruleSlot != null && ruleSlot.charsetReference()
                    ? buildCharsetReferencePlan(context, ruleSlot, c3Result) : null;
            case COLLATIONS -> ruleSlot != null && ruleSlot.collationReference()
                    ? buildCollationReferencePlan(context, ruleSlot, c3Result) : null;
            default -> null;
        };
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildColumnLikePlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        if (ruleSlot != null && ruleSlot.insertValueExpression()) {
            return buildInsertValueExpressionPlan(context, ruleSlot, c3Result);
        }
        if ((ruleSlot != null && ruleSlot.columnReference()) || qualifiedDotColumnScope(context, ruleSlot)) {
            return buildColumnReferencePlan(context, ruleSlot, c3Result);
        }
        return null;
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildDataTypeLikePlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        if (ruleSlot != null && ruleSlot.dataTypeReference()) {
            return buildDataTypeReferencePlan(context, ruleSlot, c3Result);
        }
        MysqlSqlCompletionCandidateBuildResult dataTypes = buildDataTypesFromC3(context, c3Result);
        if (dataTypes.candidates().isEmpty()) {
            return null;
        }
        return MysqlSqlCompletionCandidateBuildPlan.primary(dataTypes);
    }

    private static boolean hasIntent(SqlCompletionCandidatePlan candidatePlan,
                                     SqlCompletionIntentTypeEnum intentType) {
        return candidatePlan != null && candidatePlan.items().stream()
                .anyMatch(item -> item != null && item.intent() != null
                        && SqlCompletionIntentTypeEnum.from(item.intent().type()) == intentType);
    }

    private static MysqlSqlCompletionCandidateBuildPlan fallbackToCollectedCandidates(
            List<SqlCompletionCandidate> candidates,
            MysqlSqlCompletionCandidateBuildPlan unsupportedPlan) {
        if (candidates.isEmpty()) {
            return unsupportedPlan;
        }
        return buildPlan(candidates);
    }

    private static List<SqlCompletionCandidate> snippetCandidates(MysqlSqlCompletionCandidateContext context,
                                                                  MysqlSqlCompletionRuleSlot ruleSlot,
                                                                  SqlCompletionCandidates c3Result) {
        if (ruleSlotType(ruleSlot) == MysqlSqlCompletionRuleSlotTypeEnum.ALIAS_DECLARATION
                || ruleSlotType(ruleSlot) == MysqlSqlCompletionRuleSlotTypeEnum.ROUTINE_LOCAL_SYMBOL) {
            return List.of();
        }
        return MysqlSqlCompletionSnippetCandidateProvider.build(context, c3Result);
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildPlan(List<SqlCompletionCandidate> candidates) {
        if (candidates.isEmpty()) {
            return MysqlSqlCompletionCandidateBuildPlan.empty();
        }
        return MysqlSqlCompletionCandidateBuildPlan.primary(MysqlSqlCompletionCandidateBuildResult.success(candidates));
    }

    private static boolean hasAliasCandidate(MysqlSqlCompletionCandidateBuildPlan buildPlan) {
        return buildPlan != null && buildPlan.candidates().stream()
                .anyMatch(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.ALIAS);
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildRoutineLocalSymbols(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot) {
        if (!acceptsLocalSymbols(ruleSlotType(ruleSlot))) {
            return MysqlSqlCompletionCandidateBuildPlan.empty();
        }
        MysqlSqlCompletionCandidateBuildResult localSymbols =
                MysqlSqlCompletionRoutineLocalSymbolCandidateProvider.build(context);
        return MysqlSqlCompletionCandidateBuildPlan.primary(localSymbols);
    }

    private static boolean acceptsLocalSymbols(MysqlSqlCompletionRuleSlotTypeEnum ruleSlotType) {
        return ruleSlotType == MysqlSqlCompletionRuleSlotTypeEnum.UNKNOWN
                || ruleSlotType == MysqlSqlCompletionRuleSlotTypeEnum.COLUMN_REFERENCE
                || ruleSlotType == MysqlSqlCompletionRuleSlotTypeEnum.INSERT_VALUE_EXPRESSION
                || ruleSlotType == MysqlSqlCompletionRuleSlotTypeEnum.ROUTINE_LOCAL_SYMBOL;
    }

    private static MysqlSqlCompletionCandidateBuildResult buildDataTypesFromC3(
            MysqlSqlCompletionCandidateContext context,
            SqlCompletionCandidates c3Result) {
        return MysqlSqlCompletionDataTypeCandidateProvider.build(context, c3Result);
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildObjectReferencePlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        return MysqlSqlCompletionCandidateBuildPlan.primary(buildObjectReference(context, ruleSlot));
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildTableReferencePlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        return MysqlSqlCompletionCandidateBuildPlan.primary(MysqlSqlCompletionTableCandidateProvider.build(context));
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildTableDeclarationPlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        return MysqlSqlCompletionCandidateBuildPlan.primary(buildTableDeclaration(context));
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildDataTypeReferencePlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        return MysqlSqlCompletionCandidateBuildPlan.primary(MysqlSqlCompletionDataTypeCandidateProvider.build(context,
                c3Result));
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildCharsetReferencePlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        return MysqlSqlCompletionCandidateBuildPlan.primary(MysqlSqlCompletionCharsetCandidateProvider.build(context,
                c3Result));
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildCollationReferencePlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        return MysqlSqlCompletionCandidateBuildPlan.primary(MysqlSqlCompletionCollationCandidateProvider.build(context,
                c3Result));
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildInsertValueExpressionPlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        return MysqlSqlCompletionColumnReferenceCandidateProvider.buildInsertValueExpression(context);
    }

    private static MysqlSqlCompletionCandidateBuildPlan buildColumnReferencePlan(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            SqlCompletionCandidates c3Result) {
        return MysqlSqlCompletionColumnReferenceCandidateProvider.build(context);
    }

    private static List<SqlCompletionCandidate> blankPrefixRelationAliases(
            MysqlSqlCompletionCandidateContext context,
            MysqlSqlCompletionRuleSlot ruleSlot,
            boolean blankPrefix) {
        if (!blankPrefix || ruleSlot == null || !ruleSlot.columnReference()) {
            return List.of();
        }
        return MysqlSqlCompletionColumnReferenceCandidateProvider.buildBlankPrefixRelationAliases(context).candidates();
    }

    private static MysqlSqlCompletionCandidateBuildResult buildObjectReference(MysqlSqlCompletionCandidateContext context,
                                                                              MysqlSqlCompletionRuleSlot ruleSlot) {
        if (callableObjectReference(context, ruleSlot)) {
            return MysqlSqlCompletionObjectCandidateProvider.buildCallable(context, ruleSlot.metadataType());
        }
        return MysqlSqlCompletionObjectCandidateProvider.build(context, ruleSlot.metadataType());
    }

    private static MysqlSqlCompletionCandidateBuildResult buildTableDeclaration(
            MysqlSqlCompletionCandidateContext context) {
        if (context.cursorContext().dotScoped()) {
            return MysqlSqlCompletionCandidateBuildResult.empty();
        }
        return MysqlSqlCompletionTableCandidateProvider.buildQualifiers(context);
    }

    private static boolean callableObjectReference(MysqlSqlCompletionCandidateContext context,
                                                   MysqlSqlCompletionRuleSlot ruleSlot) {
        if (ruleSlot.metadataType() == SqlCompletionCandidateTypeEnum.PROCEDURE) {
            return currentRulePathContains(context, MySqlParser.RULE_callStatement);
        }
        if (ruleSlot.metadataType() == SqlCompletionCandidateTypeEnum.FUNCTION) {
            return MysqlSqlCompletionCallableCandidateSupport.hasArgumentListAfterReplacement(context);
        }
        return false;
    }

    private static boolean qualifiedDotColumnScope(MysqlSqlCompletionCandidateContext context,
                                                   MysqlSqlCompletionRuleSlot ruleSlot) {
        if (context == null || context.cursorContext() == null || !context.cursorContext().dotScoped()
                || !context.cursorContext().hasScopedObject()) {
            return false;
        }
        MysqlSqlCompletionRuleSlotTypeEnum ruleSlotType = ruleSlotType(ruleSlot);
        return ruleSlotType == MysqlSqlCompletionRuleSlotTypeEnum.COLUMN_REFERENCE
                || ruleSlotType == MysqlSqlCompletionRuleSlotTypeEnum.INSERT_VALUE_EXPRESSION;
    }

    private static List<SqlCompletionCandidate> keywordCandidates(MysqlSqlCompletionCandidateContext context,
                                                                  MysqlSqlCompletionRuleSlot ruleSlot,
                                                                  SqlCompletionCandidates c3Result) {
        if (context.cursorContext().dotScoped()) {
            return List.of();
        }
        if (ruleSlotType(ruleSlot) == MysqlSqlCompletionRuleSlotTypeEnum.ALIAS_DECLARATION) {
            return List.of();
        }
        if (ruleSlotType(ruleSlot) == MysqlSqlCompletionRuleSlotTypeEnum.ROUTINE_LOCAL_SYMBOL) {
            return List.of();
        }
        return MysqlSqlCompletionSyntaxCandidateCollector.collect(context, c3Result);
    }

    private static List<SqlCompletionCandidate> builtinFunctionCandidates(MysqlSqlCompletionCandidateContext context,
                                                                          SqlCompletionCandidates c3Result) {
        if (ruleSlotType(context.ruleSlot()) == MysqlSqlCompletionRuleSlotTypeEnum.ROUTINE_LOCAL_SYMBOL) {
            return List.of();
        }
        if (context.cursorContext().dotScoped()
                || !MysqlSqlCompletionFunctionTokenConfig.hasCurrentFunctionRule(c3Result)) {
            return List.of();
        }
        return MysqlSqlCompletionFunctionCandidateProvider.build(context);
    }

    private static MysqlSqlCompletionRuleSlotTypeEnum ruleSlotType(MysqlSqlCompletionRuleSlot ruleSlot) {
        return ruleSlot == null ? MysqlSqlCompletionRuleSlotTypeEnum.UNKNOWN : ruleSlot.type();
    }

    public static boolean isBlockedBlankPrefix(MysqlSqlCompletionCandidateContext context) {
        if (context == null || context.cursorContext() == null) {
            return true;
        }
        return isBlockedBlankPrefix(context, context.ruleSlot());
    }

    private static boolean isBlockedBlankPrefix(MysqlSqlCompletionCandidateContext context,
                                                MysqlSqlCompletionRuleSlot ruleSlot) {
        if (!StringUtils.isBlank(context.prefix()) || context.cursorContext().dotScoped()) {
            return false;
        }
        return !allowsBlankPrefix(ruleSlot);
    }

    private static boolean isBlankPrefix(MysqlSqlCompletionCandidateContext context) {
        return context != null
                && context.cursorContext() != null
                && StringUtils.isBlank(context.prefix())
                && !context.cursorContext().dotScoped();
    }

    private static boolean allowsBlankPrefix(MysqlSqlCompletionRuleSlot ruleSlot) {
        return ruleSlot != null
                && (ruleSlot.tableReference()
                || ruleSlot.objectReference()
                || ruleSlot.columnReference());
    }

    private static boolean currentRulePathContains(MysqlSqlCompletionCandidateContext context, int rule) {
        if (context == null || context.c3Result() == null || context.c3Result().rules() == null) {
            return false;
        }
        return context.c3Result().rules().values().stream()
                .map(SqlCompletionCandidates.RuleCandidate::ruleList)
                .anyMatch(ruleList -> ruleList.contains(rule));
    }

    private record CandidateBuildHandler(ISlotMatcher matcher,
                                         CandidateBuildResolver resolver) {

        private static CandidateBuildHandler of(ISlotMatcher matcher, CandidateBuildResolver resolver) {
            return new CandidateBuildHandler(matcher, resolver);
        }

        private boolean matches(MysqlSqlCompletionRuleSlot ruleSlot) {
            return ruleSlot != null && matcher.matches(ruleSlot);
        }

        private MysqlSqlCompletionCandidateBuildPlan build(MysqlSqlCompletionCandidateContext context,
                                                           MysqlSqlCompletionRuleSlot ruleSlot,
                                                           SqlCompletionCandidates c3Result) {
            return resolver.build(context, ruleSlot, c3Result);
        }
    }

    @FunctionalInterface
    private interface ISlotMatcher {
        boolean matches(MysqlSqlCompletionRuleSlot ruleSlot);
    }

    @FunctionalInterface
    private interface CandidateBuildResolver {
        MysqlSqlCompletionCandidateBuildPlan build(MysqlSqlCompletionCandidateContext context,
                                                   MysqlSqlCompletionRuleSlot ruleSlot,
                                                   SqlCompletionCandidates c3Result);
    }
}
