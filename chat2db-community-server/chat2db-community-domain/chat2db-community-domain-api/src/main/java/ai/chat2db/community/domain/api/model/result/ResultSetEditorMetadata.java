package ai.chat2db.community.domain.api.model.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ResultSetEditorMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    private String editorType;

    private List<ResultSetEditorOption> editorOptions;
}
