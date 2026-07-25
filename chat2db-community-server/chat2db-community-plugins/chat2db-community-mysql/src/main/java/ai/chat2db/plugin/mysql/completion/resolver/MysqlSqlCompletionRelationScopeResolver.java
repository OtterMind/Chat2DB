package ai.chat2db.plugin.mysql.completion.resolver;

import ai.chat2db.plugin.mysql.model.completion.scope.MysqlSqlCompletionRelationScope;
import ai.chat2db.plugin.mysql.completion.util.MysqlSqlCompletionTokenUtil;
import ai.chat2db.mysql.parser.base.MySqlLexer;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCursorContext;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionStatementWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.antlr.v4.runtime.Token;


public final class MysqlSqlCompletionRelationScopeResolver {

    private static final Set<Integer> RESERVED_ALIAS_BOUNDARY_TOKENS = Set.of(
            MySqlLexer.WHERE,
            MySqlLexer.ON,
            MySqlLexer.JOIN,
            MySqlLexer.USE,
            MySqlLexer.IGNORE,
            MySqlLexer.FORCE,
            MySqlLexer.INNER,
            MySqlLexer.LEFT,
            MySqlLexer.RIGHT,
            MySqlLexer.NATURAL,
            MySqlLexer.STRAIGHT_JOIN,
            MySqlLexer.FULL,
            MySqlLexer.CROSS,
            MySqlLexer.GROUP,
            MySqlLexer.ORDER,
            MySqlLexer.HAVING,
            MySqlLexer.LIMIT,
            MySqlLexer.SET,
            MySqlLexer.VALUES,
            MySqlLexer.VALUE,
            MySqlLexer.THEN,
            MySqlLexer.ELSE,
            MySqlLexer.END,
            MySqlLexer.END_SYMBOLE,
            MySqlLexer.IF,
            MySqlLexer.CASE,
            MySqlLexer.LOOP,
            MySqlLexer.SEMI,
            MySqlLexer.COMMA,
            MySqlLexer.USING,
            MySqlLexer.PARTITION,
            MySqlLexer.REFERENCES);

    private static final Set<Integer> INDEX_HINT_TOKENS = Set.of(
            MySqlLexer.USE,
            MySqlLexer.IGNORE,
            MySqlLexer.FORCE);

    private MysqlSqlCompletionRelationScopeResolver() {
    }

    public static MysqlSqlCompletionRelationScope resolve(SqlCompletionStatementWindow window,
                                                          SqlCompletionCursorContext cursorContext) {
        if (window == null || cursorContext == null || !cursorContext.admitted()) {
            return MysqlSqlCompletionRelationScope.empty();
        }
        List<Token> tokens = MysqlSqlCompletionTokenUtil.defaultTokens(window.parseSql());
        int cursor = Math.max(0, Math.min(cursorContext.replaceStart(), window.parseSql().length()));
        MysqlSqlCompletionRelationScope ddlScope = resolveDdlFocusedScope(tokens, cursor);
        if (ddlScope != null) {
            return ddlScope;
        }
        Optional<MysqlSqlCompletionRelationScope.Relation> unionOrderResult =
                MysqlSqlCompletionLocalResultSetResolver.resolveUnionOrderResult(tokens, cursor);
        if (unionOrderResult.isPresent()) {
            return new MysqlSqlCompletionRelationScope(List.of(unionOrderResult.get()));
        }
        SelectRange activeSelect = activeSelectRange(tokens, cursor);
        boolean allowForwardFrom = activeSelect != null && activeSelect.beforeFrom();
        int relationScanCursor = allowForwardFrom ? activeSelect.endOffset() : cursor;
        List<MysqlSqlCompletionRelationScope.Relation> relations = new ArrayList<>();
        relations.addAll(MysqlSqlCompletionLocalResultSetResolver.resolve(tokens, cursor));
        if (allowForwardFrom) {
            relations.addAll(MysqlSqlCompletionLocalResultSetResolver.resolveDerivedTables(tokens, relationScanCursor));
        }
        MysqlSqlCompletionLocalResultSetResolver.resolveCurrentProjection(tokens, cursor).ifPresent(relations::add);
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (activeSelect != null && token.getStartIndex() >= activeSelect.endOffset()) {
                break;
            }
            if (token.getStartIndex() >= cursor && !allowForwardFrom) {
                break;
            }
            if (token.getType() == MySqlLexer.LR_BRACKET
                    && MysqlSqlCompletionTokenUtil.startsSubquery(tokens, index)) {
                int closeIndex = MysqlSqlCompletionTokenUtil.matchingRightBracket(tokens, index);
                if (closeIndex > index && !MysqlSqlCompletionTokenUtil.containsCursor(tokens, index, closeIndex,
                        cursor)) {
                    index = closeIndex;
                }
                continue;
            }
            if (!isRelationIntroducer(tokens, index)) {
                continue;
            }
            RelationCandidate relationCandidate = relationAfter(tokens, index, true);
            if (relationCandidate == null) {
                continue;
            }
            if (isInProgressRelationCandidate(tokens, relationCandidate, cursorContext)) {
                continue;
            }
            MysqlSqlCompletionRelationScope.Relation relation = relationCandidate.relation();
            relations.add(localRelationWithAlias(relations, relation).orElse(relation));
            index = relationCandidate.tableEndIndex();
        }
        return new MysqlSqlCompletionRelationScope(relations);
    }

    private static boolean isInProgressRelationCandidate(List<Token> tokens,
                                                         RelationCandidate relationCandidate,
                                                         SqlCompletionCursorContext cursorContext) {
        if (relationCandidate == null || cursorContext == null || cursorContext.prefix() == null
                || cursorContext.prefix().isBlank()) {
            return false;
        }
        Token tableToken = tokens.get(relationCandidate.tableEndIndex());
        return tableToken.getStartIndex() <= cursorContext.replaceStart()
                && cursorContext.replaceEnd() <= tableToken.getStopIndex() + 1
                && cursorContext.prefix().equalsIgnoreCase(MysqlSqlCompletionTokenUtil.stripQuote(
                MysqlSqlCompletionTokenUtil.stripLeadingDot(tableToken.getText())));
    }

    private static Optional<MysqlSqlCompletionRelationScope.Relation> localRelationWithAlias(
            List<MysqlSqlCompletionRelationScope.Relation> relations,
            MysqlSqlCompletionRelationScope.Relation relation) {
        return relations.stream()
                .filter(MysqlSqlCompletionRelationScope.Relation::local)
                .filter(localRelation -> localRelation.matches(relation.table()))
                .findFirst()
                .map(localRelation -> MysqlSqlCompletionRelationScope.Relation.local(
                        localRelation.table(), relation.alias(), localRelation.columns()));
    }

    private static SelectRange activeSelectRange(List<Token> tokens, int cursor) {
        SelectAnchor selectAnchor = activeSelectAnchor(tokens, cursor);
        if (selectAnchor == null) {
            return null;
        }
        int selectIndex = selectAnchor.index();
        int selectDepth = selectAnchor.depth();
        int endOffset = tokens.isEmpty() ? cursor : tokens.get(tokens.size() - 1).getStopIndex() + 1;
        boolean seenFrom = false;
        int currentDepth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (index <= selectIndex) {
                currentDepth = MysqlSqlCompletionTokenUtil.updateBracketDepth(currentDepth, token);
                continue;
            }
            if (currentDepth < selectDepth) {
                endOffset = token.getStartIndex();
                break;
            }
            if (currentDepth == selectDepth) {
                if (token.getType() == MySqlLexer.SEMI) {
                    endOffset = token.getStartIndex();
                    break;
                }
                if (token.getType() == MySqlLexer.UNION) {
                    endOffset = token.getStartIndex();
                    break;
                }
                if (token.getType() == MySqlLexer.FROM && token.getStartIndex() < cursor) {
                    seenFrom = true;
                }
            }
            currentDepth = MysqlSqlCompletionTokenUtil.updateBracketDepth(currentDepth, token);
        }
        if (cursor > endOffset) {
            return null;
        }
        return new SelectRange(selectIndex, !seenFrom, endOffset);
    }

    private static SelectAnchor activeSelectAnchor(List<Token> tokens, int cursor) {
        int depth = 0;
        SelectAnchor result = null;
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.getStartIndex() >= cursor) {
                break;
            }
            if (token.getType() == MySqlLexer.SELECT) {
                SelectRange range = selectRange(tokens, index, depth, cursor);
                if (range != null) {
                    result = new SelectAnchor(index, depth);
                }
            }
            depth = MysqlSqlCompletionTokenUtil.updateBracketDepth(depth, token);
        }
        return result;
    }

    private static SelectRange selectRange(List<Token> tokens, int selectIndex, int selectDepth, int cursor) {
        int endOffset = tokens.isEmpty() ? cursor : tokens.get(tokens.size() - 1).getStopIndex() + 1;
        boolean seenFrom = false;
        int currentDepth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (index <= selectIndex) {
                currentDepth = MysqlSqlCompletionTokenUtil.updateBracketDepth(currentDepth, token);
                continue;
            }
            if (currentDepth < selectDepth) {
                endOffset = token.getStartIndex();
                break;
            }
            if (currentDepth == selectDepth) {
                if (token.getType() == MySqlLexer.SEMI || token.getType() == MySqlLexer.UNION) {
                    endOffset = token.getStartIndex();
                    break;
                }
                if (token.getType() == MySqlLexer.FROM && token.getStartIndex() < cursor) {
                    seenFrom = true;
                }
            }
            currentDepth = MysqlSqlCompletionTokenUtil.updateBracketDepth(currentDepth, token);
        }
        if (cursor > endOffset) {
            return null;
        }
        return new SelectRange(selectIndex, !seenFrom, endOffset);
    }

    private static boolean cursorInsideReferenceColumnList(List<Token> tokens, int referencesIndex, int cursor) {
        RelationCandidate relationCandidate = relationAfter(tokens, referencesIndex, false);
        if (relationCandidate == null
                || !MysqlSqlCompletionTokenUtil.tokenEndsAtOrBefore(tokens.get(relationCandidate.tableEndIndex()),
                cursor)) {
            return false;
        }
        int openIndex = MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, relationCandidate.tableEndIndex() + 1);
        if (openIndex < 0 || tokens.get(openIndex).getType() != MySqlLexer.LR_BRACKET) {
            return false;
        }
        int closeIndex = MysqlSqlCompletionTokenUtil.matchingRightBracket(tokens, openIndex);
        if (closeIndex < 0) {
            return tokens.get(openIndex).getStartIndex() < cursor;
        }
        return MysqlSqlCompletionTokenUtil.containsCursor(tokens, openIndex, closeIndex, cursor);
    }

    private static int skipIndexHint(List<Token> tokens, int start) {
        int index = start;
        while (index >= 0 && index < tokens.size() && INDEX_HINT_TOKENS.contains(tokens.get(index).getType())) {
            int bracketIndex = MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, index + 1);
            if (bracketIndex >= 0
                    && (tokens.get(bracketIndex).getType() == MySqlLexer.INDEX
                    || tokens.get(bracketIndex).getType() == MySqlLexer.KEY)) {
                bracketIndex = MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, bracketIndex + 1);
            }
            bracketIndex = skipIndexHintType(tokens, bracketIndex);
            if (bracketIndex < 0 || tokens.get(bracketIndex).getType() != MySqlLexer.LR_BRACKET) {
                return index;
            }
            int closeIndex = MysqlSqlCompletionTokenUtil.matchingRightBracket(tokens, bracketIndex);
            if (closeIndex < 0) {
                return tokens.size();
            }
            index = MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, closeIndex + 1);
        }
        return index;
    }

    private static int skipIndexHintType(List<Token> tokens, int index) {
        if (index < 0 || index >= tokens.size() || tokens.get(index).getType() != MySqlLexer.FOR) {
            return index;
        }
        index = MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, index + 1);
        if (index < 0 || index >= tokens.size()) {
            return index;
        }
        int tokenType = tokens.get(index).getType();
        if (tokenType != MySqlLexer.JOIN && tokenType != MySqlLexer.ORDER && tokenType != MySqlLexer.GROUP) {
            return index;
        }
        index = MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, index + 1);
        if (index >= 0 && index < tokens.size() && tokens.get(index).getType() == MySqlLexer.BY) {
            return MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, index + 1);
        }
        return index;
    }

    private static boolean isRelationIntroducer(List<Token> tokens, int index) {
        Token token = tokens.get(index);
        return switch (token.getType()) {
            case MySqlLexer.FROM,
                    MySqlLexer.JOIN,
                    MySqlLexer.STRAIGHT_JOIN,
                    MySqlLexer.INTO -> true;
            case MySqlLexer.COMMA -> isFromRelationSeparator(tokens, index);
            case MySqlLexer.UPDATE -> !isOnDuplicateKeyUpdate(tokens, index);
            default -> false;
        };
    }

    private static boolean isFromRelationSeparator(List<Token> tokens, int commaIndex) {
        int depth = MysqlSqlCompletionTokenUtil.depthAtOffset(tokens, tokens.get(commaIndex).getStartIndex());
        int fromIndex = MysqlSqlCompletionTokenUtil.lastDefaultIndexBeforeAtDepth(tokens,
                tokens.get(commaIndex).getStartIndex(), depth, MySqlLexer.FROM);
        if (fromIndex < 0) {
            return false;
        }
        int boundaryIndex = MysqlSqlCompletionTokenUtil.lastDefaultIndexBeforeAtDepth(tokens,
                tokens.get(commaIndex).getStartIndex(), depth, MySqlLexer.WHERE, MySqlLexer.GROUP,
                MySqlLexer.HAVING, MySqlLexer.WINDOW, MySqlLexer.ORDER, MySqlLexer.LIMIT, MySqlLexer.UNION,
                MySqlLexer.INTO);
        return boundaryIndex < fromIndex;
    }

    private static boolean isOnDuplicateKeyUpdate(List<Token> tokens, int index) {
        int previousIndex = MysqlSqlCompletionTokenUtil.previousDefaultIndex(tokens, index - 1);
        return previousIndex >= 0 && tokens.get(previousIndex).getType() == MySqlLexer.KEY
                && MysqlSqlCompletionTokenUtil.hasTokenBefore(tokens, tokens.get(index).getStartIndex(),
                MySqlLexer.DUPLICATE);
    }

    private static MysqlSqlCompletionRelationScope resolveDdlFocusedScope(List<Token> tokens, int cursor) {
        RelationCandidate referencedTable = referencedTableRelation(tokens, cursor);
        if (referencedTable != null) {
            return new MysqlSqlCompletionRelationScope(List.of(referencedTable.relation()));
        }
        RelationCandidate createIndexTable = createIndexTableRelation(tokens, cursor);
        if (createIndexTable != null) {
            return new MysqlSqlCompletionRelationScope(List.of(createIndexTable.relation()));
        }
        RelationCandidate alterTable = alterTableRelation(tokens, cursor);
        if (alterTable != null) {
            return new MysqlSqlCompletionRelationScope(List.of(alterTable.relation()));
        }
        return null;
    }

    private static RelationCandidate referencedTableRelation(List<Token> tokens, int cursor) {
        int referencesIndex = MysqlSqlCompletionTokenUtil.lastDefaultIndexBefore(tokens, cursor, MySqlLexer.REFERENCES);
        if (referencesIndex < 0 || !cursorInsideReferenceColumnList(tokens, referencesIndex, cursor)) {
            return null;
        }
        return relationAfterCursorPassedTable(tokens, referencesIndex, cursor);
    }

    private static RelationCandidate createIndexTableRelation(List<Token> tokens, int cursor) {
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.getStartIndex() >= cursor) {
                break;
            }
            if (token.getType() != MySqlLexer.ON
                    || MysqlSqlCompletionTokenUtil.missingOrderedTokenTypesBefore(tokens, index,
                    MySqlLexer.CREATE, MySqlLexer.INDEX)) {
                continue;
            }
            RelationCandidate relationCandidate = relationAfterCursorPassedTable(tokens, index, cursor);
            if (relationCandidate != null) {
                return relationCandidate;
            }
        }
        return null;
    }

    private static RelationCandidate alterTableRelation(List<Token> tokens, int cursor) {
        int alterIndex = MysqlSqlCompletionTokenUtil.lastDefaultIndexBefore(tokens, cursor, MySqlLexer.ALTER);
        if (alterIndex < 0) {
            return null;
        }
        int tableKeywordIndex = MysqlSqlCompletionTokenUtil.nextDefaultIndexBefore(tokens, alterIndex + 1, cursor,
                MySqlLexer.TABLE);
        if (tableKeywordIndex < 0) {
            return null;
        }
        return relationAfterCursorPassedTable(tokens, tableKeywordIndex, cursor);
    }

    private static RelationCandidate relationAfterCursorPassedTable(List<Token> tokens, int introducerIndex, int cursor) {
        RelationCandidate relationCandidate = relationAfter(tokens, introducerIndex, false);
        if (relationCandidate == null) {
            return null;
        }
        return MysqlSqlCompletionTokenUtil.tokenEndsAtOrBefore(tokens.get(relationCandidate.tableEndIndex()), cursor)
                ? relationCandidate : null;
    }

    private static RelationCandidate relationAfter(List<Token> tokens, int introducerIndex, boolean allowAlias) {
        int tableIndex = tableNameIndexAfterIntroducer(tokens, introducerIndex);
        if (tableIndex < 0) {
            return null;
        }
        Token tableToken = tokens.get(tableIndex);
        if (!MysqlSqlCompletionTokenUtil.isUnqualifiedIdentifierToken(tableToken)
                || MysqlSqlCompletionTokenUtil.isCompletionDummy(tableToken)) {
            return null;
        }
        int tableEndIndex = qualifiedRelationEndIndex(tokens, tableIndex);
        QualifiedRelationName relationName = qualifiedRelationName(tokens, tableIndex, tableEndIndex);
        int aliasIndex = allowAlias ? aliasIndex(tokens, tableEndIndex + 1) : -1;
        Token aliasToken = aliasIndex < 0 ? null : tokens.get(aliasIndex);
        return new RelationCandidate(new MysqlSqlCompletionRelationScope.Relation(relationName.catalog(),
                relationName.schema(), relationName.table(), aliasToken == null ? null : aliasToken.getText()),
                tableEndIndex);
    }

    private static QualifiedRelationName qualifiedRelationName(List<Token> tokens, int start, int end) {
        List<String> parts = new ArrayList<>();
        for (int index = start; index <= end && index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.getType() == MySqlLexer.DOT) {
                continue;
            }
            if (token.getType() == MySqlLexer.DOT_ID) {
                parts.add(normalizeIdentifierPart(MysqlSqlCompletionTokenUtil.stripLeadingDot(token.getText())));
                continue;
            }
            if (MysqlSqlCompletionTokenUtil.isUnqualifiedIdentifierToken(token)) {
                parts.add(normalizeIdentifierPart(token.getText()));
            }
        }
        if (parts.size() >= 3) {
            return new QualifiedRelationName(parts.get(parts.size() - 3), parts.get(parts.size() - 2),
                    parts.get(parts.size() - 1));
        }
        if (parts.size() == 2) {
            return new QualifiedRelationName(null, parts.get(0), parts.get(1));
        }
        String table = parts.isEmpty() ? "" : parts.get(0);
        return new QualifiedRelationName(null, null, table);
    }

    private static int qualifiedRelationEndIndex(List<Token> tokens, int start) {
        if (start < 0 || start >= tokens.size()) {
            return -1;
        }
        int end = start;
        int index = start + 1;
        while (index < tokens.size()) {
            Token token = tokens.get(index);
            if (token.getType() == MySqlLexer.DOT_ID) {
                end = index;
                index++;
                continue;
            }
            if (token.getType() == MySqlLexer.DOT
                    && index + 1 < tokens.size()
                    && MysqlSqlCompletionTokenUtil.isUnqualifiedIdentifierToken(tokens.get(index + 1))) {
                end = index + 1;
                index += 2;
                continue;
            }
            break;
        }
        return end;
    }

    private static String normalizeIdentifierPart(String value) {
        return MysqlSqlCompletionTokenUtil.stripQuote(value);
    }

    private static int tableNameIndexAfterIntroducer(List<Token> tokens, int introducerIndex) {
        int tableIndex = MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, introducerIndex + 1);
        if (isIntoTableKeyword(tokens, introducerIndex, tableIndex)) {
            return MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, tableIndex + 1);
        }
        if (tokens.get(introducerIndex).getType() == MySqlLexer.FROM
                || tokens.get(introducerIndex).getType() == MySqlLexer.JOIN
                || tokens.get(introducerIndex).getType() == MySqlLexer.STRAIGHT_JOIN) {
            return skipJoinModifiers(tokens, tableIndex);
        }
        return tableIndex;
    }

    private static int skipJoinModifiers(List<Token> tokens, int tableIndex) {
        int index = tableIndex;
        while (index >= 0 && index < tokens.size()) {
            int tokenType = tokens.get(index).getType();
            if (tokenType != MySqlLexer.NATURAL
                    && tokenType != MySqlLexer.INNER
                    && tokenType != MySqlLexer.LEFT
                    && tokenType != MySqlLexer.RIGHT
                    && tokenType != MySqlLexer.FULL
                    && tokenType != MySqlLexer.CROSS
                    && tokenType != MySqlLexer.OUTER
                    && tokenType != MySqlLexer.STRAIGHT_JOIN) {
                return index;
            }
            index = MysqlSqlCompletionTokenUtil.nextDefaultIndex(tokens, index + 1);
        }
        return -1;
    }

    private static boolean isIntoTableKeyword(List<Token> tokens, int introducerIndex, int tableIndex) {
        return tableIndex >= 0
                && tokens.get(introducerIndex).getType() == MySqlLexer.INTO
                && tokens.get(tableIndex).getType() == MySqlLexer.TABLE;
    }

    private static int aliasIndex(List<Token> tokens, int start) {
        if (start >= tokens.size()) {
            return -1;
        }
        int index = skipIndexHint(tokens, start);
        if (index < 0 || index >= tokens.size()) {
            return -1;
        }
        Token token = tokens.get(index);
        if (token.getType() == MySqlLexer.AS) {
            index++;
            if (index >= tokens.size()) {
                return -1;
            }
            token = tokens.get(index);
        }
        if (!MysqlSqlCompletionTokenUtil.isUnqualifiedIdentifierToken(token)
                || RESERVED_ALIAS_BOUNDARY_TOKENS.contains(token.getType())
                || MysqlSqlCompletionTokenUtil.isCompletionDummy(token)) {
            return -1;
        }
        return index;
    }

    private record RelationCandidate(MysqlSqlCompletionRelationScope.Relation relation,
                                     int tableEndIndex) {
    }

    private record SelectRange(int selectIndex,
                               boolean beforeFrom,
                               int endOffset) {
    }

    private record SelectAnchor(int index,
                                int depth) {
    }

    private record QualifiedRelationName(String catalog,
                                         String schema,
                                         String table) {
    }
}
