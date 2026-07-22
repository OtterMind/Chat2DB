package ai.chat2db.plugin.mysql.completion.provider.value;

import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionInsertTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.plugin.mysql.completion.hint.MysqlSqlCompletionEditorHintBuilder;
import ai.chat2db.plugin.mysql.completion.value.MysqlSqlCompletionValueDefaults;
import ai.chat2db.plugin.mysql.model.completion.context.MysqlSqlCompletionCandidateContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;

public final class MysqlSqlCompletionValueCandidateProvider {

    private MysqlSqlCompletionValueCandidateProvider() {
    }

    public static List<SqlCompletionCandidate> build(MysqlSqlCompletionCandidateContext context) {
        if (context == null || context.cursorContext() == null || context.cursorContext().dotScoped()) {
            return List.of();
        }
        SqlCompletionEditorHint.Item field = new MysqlSqlCompletionEditorHintBuilder().buildValueHints(context).stream()
                .flatMap(hint -> hint.getItems().stream())
                .filter(SqlCompletionEditorHint.Item::isActive)
                .findFirst()
                .orElse(null);
        if (field == null) {
            return List.of();
        }

        List<ValueTemplate> templates = templates(field.getFieldType());
        String prefix = StringUtils.defaultString(context.prefix()).toLowerCase(Locale.ROOT);
        Predicate<ValueTemplate> matchesPrefix = template -> prefix.isBlank()
                || template.label().toLowerCase(Locale.ROOT).startsWith(prefix)
                || template.insertText().toLowerCase(Locale.ROOT).startsWith(prefix);
        return templates.stream()
                .filter(matchesPrefix)
                .map(template -> candidate(field, template))
                .toList();
    }

    private static List<ValueTemplate> templates(String fieldType) {
        String type = MysqlSqlCompletionValueDefaults.normalizeType(fieldType);
        List<ValueTemplate> templates = new ArrayList<>();
        if (MysqlSqlCompletionValueDefaults.isBoolean(type)) {
            templates.add(plain("TRUE", 1));
            templates.add(plain("FALSE", 2));
        } else if (MysqlSqlCompletionValueDefaults.isInteger(type)) {
            templates.add(plain("0", 1));
        } else if (MysqlSqlCompletionValueDefaults.isDecimal(type)) {
            templates.add(plain("0.0", 1));
        } else if ("DATE".equals(type)) {
            templates.add(plain("CURRENT_DATE", 1));
            templates.add(snippet("'YYYY-MM-DD'", "'${1:YYYY-MM-DD}'$0", 2));
        } else if (MysqlSqlCompletionValueDefaults.isDateTime(type)) {
            templates.add(plain("CURRENT_TIMESTAMP", 1));
            templates.add(snippet("'YYYY-MM-DD HH:MM:SS'", "'${1:YYYY-MM-DD HH:MM:SS}'$0", 2));
        } else if (MysqlSqlCompletionValueDefaults.isTime(type)) {
            templates.add(plain("CURRENT_TIME", 1));
            templates.add(snippet("'HH:MM:SS'", "'${1:HH:MM:SS}'$0", 2));
        } else if ("YEAR".equals(type)) {
            templates.add(plain("YEAR(CURRENT_DATE)", 1));
            templates.add(snippet("YYYY", "${1:YYYY}$0", 2));
        } else if (MysqlSqlCompletionValueDefaults.isBinary(type)) {
            templates.add(snippet("X'00'", "X'${1:00}'$0", 1));
        } else if ("JSON".equals(type)) {
            templates.add(plain("JSON_OBJECT()", 1));
            templates.add(plain("JSON_ARRAY()", 2));
            templates.add(snippet("'{}'", "'${1:{}}'$0", 3));
        } else if (MysqlSqlCompletionValueDefaults.isString(type)) {
            templates.add(snippet("'value'", "'${1:value}'$0", 1));
        }
        templates.add(plain("NULL", 90));
        templates.add(plain("DEFAULT", 91));
        return templates;
    }

    private static SqlCompletionCandidate candidate(SqlCompletionEditorHint.Item field, ValueTemplate template) {
        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.PARAMETER,
                template.label());
        candidate.setInsertText(template.insertText());
        candidate.setInsertType(template.snippet()
                ? SqlCompletionInsertTypeEnum.SNIPPET
                : SqlCompletionInsertTypeEnum.PLAIN_TEXT);
        candidate.setColumnName(field.getFieldName());
        candidate.setDataType(field.getFieldType());
        candidate.setDetail(StringUtils.isBlank(field.getFieldName()) ? null : "(" + field.getFieldName() + ")");
        candidate.setDescription(field.getFieldType());
        candidate.setSortRank(template.sortRank());
        return candidate;
    }

    private static ValueTemplate plain(String value, int sortRank) {
        return new ValueTemplate(value, value, false, sortRank);
    }

    private static ValueTemplate snippet(String label, String insertText, int sortRank) {
        return new ValueTemplate(label, insertText, true, sortRank);
    }

    private record ValueTemplate(String label, String insertText, boolean snippet, int sortRank) {

        private ValueTemplate {
            label = Objects.requireNonNull(label);
            insertText = Objects.requireNonNull(insertText);
        }
    }
}
