package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import lombok.Data;
import java.nio.charset.Charset;

@Data
public class SqlImportOptions {
    private String encoding = "AUTO";

    public SqlImportOptions validate() {
        try {
            if (!"AUTO".equals(encoding)) encoding = Charset.forName(encoding).name();
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{StringUtils.defaultString(encoding)}, e);
        }
        return this;
    }
}
