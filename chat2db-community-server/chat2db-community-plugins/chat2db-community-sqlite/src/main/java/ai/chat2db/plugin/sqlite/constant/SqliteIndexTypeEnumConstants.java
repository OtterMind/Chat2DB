package ai.chat2db.plugin.sqlite.constant;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;


public final class SqliteIndexTypeEnumConstants {

    public static final String SQL_COMMENT = "COMMENT '";
    public static final String SQL_CREATE = "CREATE ";
    public static final String SQL_DROP_INDEX = "DROP INDEX ";
    public static final String SQL_DROP_PRIMARY_KEY = "DROP PRIMARY KEY";
    public static final String SQL_ON = " ON ";

    private SqliteIndexTypeEnumConstants() {
    }
}
