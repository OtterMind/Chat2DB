package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.spi.model.datasource.ConnectInfo;

record TaskSubmission<S extends TaskSpec>(Long taskId, S spec, Context context, ConnectInfo connectInfo) {
}
