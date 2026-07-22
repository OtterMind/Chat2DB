package ai.chat2db.plugin.sqlserver.constant;

import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.util.SqlUtils;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public final class SqlServerColumnTypeEnumConstants {

    public static final String SQL_ALTER_COLUMN = "ALTER COLUMN ";
    public static final String SQL_ALTER_TABLE = "ALTER TABLE ";
    public static final String SQL_DROP_COLUMN = "DROP COLUMN ";
    public static final String SQL_DROP_CONSTRAINT = "DROP CONSTRAINT ";

    public static final String RENAME_COLUMN_SCRIPT = "exec sp_rename '[%s].[%s]','%s','COLUMN' \ngo\n";
    public static final String COLUMN_MODIFY_COMMENT_SCRIPT = "IF ((SELECT COUNT(*) FROM ::fn_listextendedproperty('MS_Description',\n" +
            "'SCHEMA', N'%s',\n" +
            "'TABLE', N'%s',\n" +
            "'COLUMN', N'%s')) > 0)\n" +
            "  EXEC sp_updateextendedproperty\n" +
            "'MS_Description', N'%s',\n" +
            "'SCHEMA', N'%s',\n" +
            "'TABLE', N'%s',\n" +
            "'COLUMN', N'%s'\n" +
            "ELSE\n" +
            "  EXEC sp_addextendedproperty\n" +
            "'MS_Description', N'%s',\n" +
            "'SCHEMA', N'%s',\n" +
            "'TABLE', N'%s',\n" +
            "'COLUMN', N'%s'\n go\n";


    private SqlServerColumnTypeEnumConstants() {
    }
}
