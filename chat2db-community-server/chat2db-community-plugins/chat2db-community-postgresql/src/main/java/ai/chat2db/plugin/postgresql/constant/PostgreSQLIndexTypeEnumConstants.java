package ai.chat2db.plugin.postgresql.constant;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;


public final class PostgreSQLIndexTypeEnumConstants {

    public static final String SQL_COMMENT_CONSTRAINT = "COMMENT ON CONSTRAINT";
    public static final String SQL_COMMENT_INDEX = "COMMENT ON INDEX";
    public static final String SQL_CREATE = "CREATE";
    public static final String SQL_DROP_CONSTRAINT = "DROP CONSTRAINT ";
    public static final String SQL_DROP_INDEX = "DROP INDEX ";
    public static final String SQL_ON = "ON ";

    private PostgreSQLIndexTypeEnumConstants() {
    }
}
