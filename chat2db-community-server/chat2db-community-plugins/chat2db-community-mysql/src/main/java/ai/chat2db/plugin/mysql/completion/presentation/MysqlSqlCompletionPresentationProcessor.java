package ai.chat2db.plugin.mysql.completion.presentation;

import ai.chat2db.plugin.mysql.completion.plan.MysqlSqlCompletionCandidatePlanExecutor;
import ai.chat2db.plugin.mysql.completion.context.MysqlSqlCompletionCandidateContextFactory;
import ai.chat2db.plugin.mysql.completion.hint.MysqlSqlCompletionEditorHintBuilder;
import ai.chat2db.plugin.mysql.model.completion.context.MysqlSqlCompletionCandidateContext;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionResponse;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.spi.parser.completion.SqlCompletionPipelineState;
import ai.chat2db.spi.ISqlCompletionPresentationProcessor;
import java.util.List;
import org.apache.commons.lang3.StringUtils;


public final class MysqlSqlCompletionPresentationProcessor implements ISqlCompletionPresentationProcessor {

    private final MysqlSqlCompletionEditorHintBuilder editorHintBuilder;
    private final MysqlSqlCompletionCandidateContextFactory contextFactory;

    public MysqlSqlCompletionPresentationProcessor(MysqlSqlCompletionEditorHintBuilder editorHintBuilder,
                                                   MysqlSqlCompletionCandidateContextFactory contextFactory) {
        this.editorHintBuilder = editorHintBuilder;
        this.contextFactory = contextFactory;
    }

    @Override
    public SqlCompletionResponse process(SqlCompletionPipelineState state) {
        MysqlSqlCompletionCandidateContext context = contextFactory.create(state);
        if (StringUtils.isBlank(context.dummySql().sql()) || !context.cursorContext().admitted()) {
            return attachEditorHints(SqlCompletionResponse.empty(), context);
        }
        SqlCompletionResponse result = MysqlSqlCompletionCandidatePlanExecutor.execute(context, state.candidatePlan());
        return attachEditorHints(result, context);
    }

    private SqlCompletionResponse attachEditorHints(SqlCompletionResponse result, MysqlSqlCompletionCandidateContext context) {
        if (result == null) {
            return result;
        }
        List<SqlCompletionEditorHint> editorHints = StringUtils.isBlank(context.prefix())
                ? editorHintBuilder.buildValueHints(context)
                : editorHintBuilder.build(context);
        if (!editorHints.isEmpty()) {
            result.setEditorHints(editorHints);
        }
        return result;
    }
}
