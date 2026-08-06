package ai.chat2db.plugin.mongodb;

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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ai.chat2db.spi.model.ExecutionTiming;
import ai.chat2db.spi.util.JdbcUtils;
import lombok.extern.slf4j.Slf4j;


import static ai.chat2db.plugin.mongodb.constant.MongodbScriptExecutorConstants.*;
@Slf4j
public class MongodbScriptExecutor extends DefaultSQLExecutor {


    private static final MongodbScriptExecutor INSTANCE = new MongodbScriptExecutor();

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Pattern EDITABLE_QUERY_PATTERN = Pattern.compile(
        "db\\.(?:([A-Za-z_$][\\w$]*)|getCollection\\((\"(?:\\\\.|[^\"\\\\])*\")\\))\\.find\\(.*\\)",
        Pattern.CASE_INSENSITIVE);

    public static MongodbScriptExecutor getInstance() {
        return INSTANCE;
    }

    public MongodbScriptExecutor() {

    }

    @Override
    public List<ExecuteResponse> executeSelectTable(SqlExecuteRequest command) {
        if (StringUtils.isEmpty(command.getTableName())) {
            return Collections.emptyList();
        }
        String sql = selectTableCommand(command.getTableName());
        command.setScript(sql);
        return execute(command);
    }

    static String selectTableCommand(String tableName) {
        return String.format(EXECUTE_SQL, MongodbSqlGuards.collectionAccessor(tableName));
    }


    String getTableName(String tableName, String sql) {

        if (StringUtils.isEmpty(tableName) && StringUtils.isEmpty(sql)) {
            return StringUtils.EMPTY;
        }
        if (StringUtils.isNotEmpty(tableName)) {
            return tableName;
        }
        return editableCollectionName(sql).orElse(StringUtils.EMPTY);
    }

    static Optional<String> editableCollectionName(String sql) {
        if (StringUtils.isEmpty(sql)) {
            return Optional.empty();
        }
        Matcher matcher = EDITABLE_QUERY_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return Optional.empty();
        }
        if (matcher.group(1) != null) {
            return Optional.of(matcher.group(1));
        }
        try {
            return Optional.ofNullable(JSON_MAPPER.readValue(matcher.group(2), String.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public List<ExecuteResponse> execute(SqlExecuteRequest command) {
        int pageNo = Optional.ofNullable(command.getPageNo()).orElse(1);
        command.setPageNo(pageNo);
        int pageSize = Optional.ofNullable(command.getPageSize()).orElse(IEasyToolsConstant.MAX_PAGE_SIZE);
        command.setPageSize(pageSize);
        List<ExecuteResponse> resultList = Lists.newArrayList();
        List<String> sqlList = parseSql(command.getScript());
        for (String originalSql : sqlList) {
            try {
                ExecuteResponse result = doExecuteCommand(originalSql, command);
                resultList.add(result);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        return resultList;
    }

    private ExecuteResponse doExecuteCommand(String sql, SqlExecuteRequest command) throws SQLException {
        Assert.notNull(sql, "SQL must not be null");
        log.info("execute:{}", sql);
        Connection connection = Chat2DBContext.getConnection();
        ExecuteResponse executeResult = ExecuteResponse.builder().sql(sql).success(Boolean.TRUE).build();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setFetchSize(IEasyToolsConstant.MAX_PAGE_SIZE);
            long startedAtEpochMs = System.currentTimeMillis();
            long executeStartedNanos = System.nanoTime();
            boolean query = stmt.execute();
            long executeDurationNanos = ExecutionTiming.elapsedNanos(executeStartedNanos);
            long fetchDurationNanos = 0L;
            executeResult.setDescription(I18nUtils.getMessage("sqlResult.success"));
            if (query) {
                long fetchStartedNanos = System.nanoTime();
                executeResult = buildQueryCommandResult(stmt, sql, command);
                fetchDurationNanos = ExecutionTiming.elapsedNanos(fetchStartedNanos);
            } else {
                executeResult.setUpdateCount(stmt.getUpdateCount());
            }
            executeResult.setExecutionMetrics(ExecutionTiming.complete(
                    ExecutionTiming.started(startedAtEpochMs), executeDurationNanos, fetchDurationNanos,
                    CollectionUtils.size(executeResult.getDataList())));
        }
        return executeResult;
    }

    private ExecuteResponse buildQueryCommandResult(Statement stmt, String sql, SqlExecuteRequest command) throws SQLException {
        List<LinkedHashMap<String, Object>> documentList = Lists.newArrayList();
        List<Header> headerList = Lists.newArrayList();
        List<ResultCell> valueList = Lists.newArrayList();
        List<List<ResultCell>> dataList = Lists.newArrayList();
        Map<String, Header> headerListMap = Maps.newLinkedHashMap();
        List<TreeMap<String, String>> dataListMap = Lists.newArrayList();
        int fromIndex = Math.max(command.getPageNo() - 1, 0) * command.getPageSize();
        int toIndex = fromIndex + command.getPageSize();
        ExecuteResponse result = ExecuteResponse.builder().sql(sql).success(Boolean.TRUE).build();
        ResultSet rs = null;
        try {
            rs = stmt.getResultSet();
            int index = 1;
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object o = rs.getObject(1);
                if (Objects.nonNull(o)) {
                    LinkedHashMap<String, Object> map = DocumentConverter.object2map(o);
                    map.put(I18nUtils.getMessage("sqlResult.rowNumber"), index++);
                    documentList.add(map);
                } else {
                    String value = rs.getString(1);
                    if (StringUtils.isEmpty(value)) {
                        continue;
                    }
                    valueList.add(ResultCell.of(value));
                    if (Objects.nonNull(rs.getMetaData())) {
                        headerList.add(Header.builder().name(rs.getMetaData().getColumnName(1)).build());
                        headerList.add(Header.builder().name(I18nUtils.getMessage("sqlResult.rowNumber"))
                            .dataType(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode()).build());
                    }
                }

                if (columnCount++ >= toIndex) {
                    break;
                }
            }

            if (CollectionUtils.isEmpty(documentList)) {
                dataList.add(valueList);
                result.setDataList(dataList);
                result.setHeaderList(headerList);
                result.setOriginalSql(command.getScript());
                result.setFuzzyTotal(String.valueOf(dataList.size()));
                result.setDescription(I18nUtils.getMessage("sqlResult.success"));
                return result;
            }
            for (Map<String, Object> doc : documentList) {
                TreeMap<String, String> row = Maps.newTreeMap();
                dataListMap.add(row);
                for (String string : doc.keySet()) {

                    headerListMap.computeIfAbsent(string, k -> Header.builder()
                        .dataType("string")
                        .name(string)
                        .build());
                    row.put(string, Objects.toString(doc.get(string)));

                }

            }
            headerListMap = insertAtFirstPosition(headerListMap, I18nUtils.getMessage("sqlResult.rowNumber"),
                Header.builder()
                    .dataType("string")
                    .name(I18nUtils.getMessage("sqlResult.rowNumber"))
                    .build());
            for (Map<String, String> stringStringMap : dataListMap) {
                List<ResultCell> dataTempList = Lists.newArrayList();
                for (Header value : headerListMap.values()) {
                    dataTempList.add(ResultCell.of(stringStringMap.get(value.getName())));
                }
                dataList.add(dataTempList);
            }
            headerList.addAll(headerListMap.values().stream().toList());
            headerList.stream().filter(header -> header.getName().equals(I18nUtils.getMessage("sqlResult.rowNumber")))
                .forEach(header -> {
                    header.setDataType(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode());
                });
            result.setHeaderList(headerList);
            result.setDataList(dataList.subList(fromIndex, Math.min(dataList.size(), toIndex)));
            String fuzzyTotal = calculateFuzzyTotal(command, result);
            result.setFuzzyTotal(fuzzyTotal);
            result.setCanEdit(canEdit(command.getScript()));
            result.setOriginalSql(command.getScript());
            result.setDescription(I18nUtils.getMessage("sqlResult.success"));
            result.setTableName(getTableName(command.getTableName(), command.getScript()));
            result.setHasNextPage(CollectionUtils.size(result.getDataList()) >= command.getPageSize());
        } finally {
            JdbcUtils.closeResultSet(rs);
        }
        return result;
    }

    private <K, V> LinkedHashMap<K, V> insertAtFirstPosition(Map<K, V> originalMap, K keyToBeFirst, V value) {
        LinkedHashMap<K, V> resultMap = new LinkedHashMap<>();
        resultMap.put(keyToBeFirst, value);
        for (Map.Entry<K, V> entry : originalMap.entrySet()) {
            if (!entry.getKey().equals(keyToBeFirst)) {
                resultMap.put(entry.getKey(), entry.getValue());
            }
        }

        return resultMap;
    }

    private String calculateFuzzyTotal(SqlExecuteRequest command, ExecuteResponse executeResult) {
        int dataSize = CollectionUtils.size(executeResult.getDataList());
        if (command.getPageSize() <= 0) {
            return Integer.toString(dataSize);
        }
        int fuzzyTotal = Math.max(command.getPageNo() - 1, 0) * command.getPageSize() + dataSize;
        if (dataSize < command.getPageSize()) {
            return Integer.toString(fuzzyTotal);
        }
        return Integer.toString(command.getPageSize()) + "+";
    }

    boolean canEdit(String sql) {
        return editableCollectionName(sql).isPresent();
    }

    private static List<String> parseSql(String sql) {
        List<String> list = Lists.newArrayList();
        try {
            return SQLParserUtils.splitAndRemoveComment(sql, null);
        } catch (Exception e) {
            list.add(SQLParserUtils.removeComment(sql, null));
            log.error("parse sql error", e);
        }
        return list;
    }
}
