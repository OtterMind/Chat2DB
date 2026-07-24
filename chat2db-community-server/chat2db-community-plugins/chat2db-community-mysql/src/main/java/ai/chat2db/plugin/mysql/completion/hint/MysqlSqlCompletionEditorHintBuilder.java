package ai.chat2db.plugin.mysql.completion.hint;

import ai.chat2db.plugin.mysql.model.completion.context.MysqlSqlCompletionCandidateContext;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import java.util.ArrayList;
import java.util.List;


public final class MysqlSqlCompletionEditorHintBuilder {

    private final MysqlSqlCompletionInsertValueHintBuilder insertValueHintBuilder =
            new MysqlSqlCompletionInsertValueHintBuilder();
    private final MysqlSqlCompletionRoutineParameterHintBuilder routineParameterHintBuilder =
            new MysqlSqlCompletionRoutineParameterHintBuilder();

    public List<SqlCompletionEditorHint> build(MysqlSqlCompletionCandidateContext context) {
        if (context == null) {
            return List.of();
        }
        List<SqlCompletionEditorHint> hints = new ArrayList<>();
        hints.addAll(buildValueHints(context));
        hints.addAll(routineParameterHintBuilder.build(context));
        return hints.isEmpty() ? List.of() : hints;
    }

    public List<SqlCompletionEditorHint> buildValueHints(MysqlSqlCompletionCandidateContext context) {
        if (context == null) {
            return List.of();
        }
        List<SqlCompletionEditorHint> hints = new ArrayList<>();
        hints.addAll(insertValueHintBuilder.build(context));
        return hints.isEmpty() ? List.of() : hints;
    }
}
