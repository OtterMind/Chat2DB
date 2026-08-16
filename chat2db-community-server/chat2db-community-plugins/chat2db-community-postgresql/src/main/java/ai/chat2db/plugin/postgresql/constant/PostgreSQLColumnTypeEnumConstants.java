package ai.chat2db.plugin.postgresql.constant;

import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.util.SqlUtils;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.*;


public final class PostgreSQLColumnTypeEnumConstants {

    public static final String SQL_ALTER_COLUMN = "ALTER COLUMN ";
    public static final String SQL_COMMENT_COLUMN = "COMMENT ON COLUMN";
    public static final String SQL_DROP_COLUMN = "DROP COLUMN ";

    private PostgreSQLColumnTypeEnumConstants() {
    }
}
