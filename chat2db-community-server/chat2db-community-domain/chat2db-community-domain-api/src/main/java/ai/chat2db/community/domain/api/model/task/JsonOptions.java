package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.nio.charset.Charset;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
public class JsonOptions extends ImportValueOptions {
    private String encoding = "UTF-8";
    private String structure = "ARRAY";
    private String dataPath = "$";
    private Boolean emptyAsNull = false;

    public JsonOptions validate() {
        validateFormats();
        try {
            encoding = Charset.forName(encoding).name();
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{StringUtils.defaultString(encoding)}, e);
        }
        if (!Set.of("ARRAY", "OBJECT", "LINES").contains(structure == null ? "" : structure)
                || emptyAsNull == null || dataPath == null
                || !dataPath.matches("\\$(?:\\.[^.\\[\\]\\s]+|\\[[0-9]+\\])*")
                || "LINES".equals(structure) && !"$".equals(dataPath)) {
            throw new BusinessException("import.preview.invalidJsonOptions");
        }
        return this;
    }
}
