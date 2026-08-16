package ai.chat2db.plugin.mongodb.constant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import ai.chat2db.community.tools.constant.IEasyToolsConstant;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.converter.DocumentConverter;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.JdbcUtils;
import lombok.extern.slf4j.Slf4j;



public final class MongodbScriptExecutorConstants {

    public static final String EXECUTE_SQL = "db.%s.find()";

    private MongodbScriptExecutorConstants() {
    }
}
