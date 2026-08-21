package ai.chat2db.plugin.sqlite.constant;

import ai.chat2db.plugin.sqlite.builder.SqliteBuilder;
import ai.chat2db.plugin.sqlite.identifier.SqliteIdentifierProcessor;
import ai.chat2db.plugin.sqlite.enums.type.SqliteCollationEnum;
import ai.chat2db.plugin.sqlite.enums.type.SqliteColumnTypeEnum;
import ai.chat2db.plugin.sqlite.enums.type.SqliteDefaultValueEnum;
import ai.chat2db.plugin.sqlite.enums.type.SqliteIndexTypeEnum;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.spi.DefaultSQLExecutor;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


public final class SqliteMetaDataConstants {

    public static final String TRIGGER_LIST_SQL = "SELECT * FROM sqlite_master WHERE type = 'trigger';";
    public static final String VIEW_DDL_SQL = "SELECT * FROM sqlite_master WHERE type = 'view' and name='%s';";
    public static final String TRIGGER_DDL_SQL = "SELECT * FROM sqlite_master WHERE type = 'trigger' and name='%s';";


    private SqliteMetaDataConstants() {
    }
}
