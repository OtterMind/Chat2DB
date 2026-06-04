package ai.chat2db.server.domain.core.impl;

import ai.chat2db.server.domain.api.param.CreateVirtualFKParam;
import ai.chat2db.server.domain.api.param.UpdateVirtualFKParam;
import ai.chat2db.server.domain.api.service.ForeignKeySyncService;
import ai.chat2db.server.domain.repository.Dbutils;
import ai.chat2db.server.domain.repository.entity.ForeignKeyDO;
import ai.chat2db.server.domain.repository.entity.VirtualForeignKeyDO;
import ai.chat2db.server.domain.repository.mapper.ForeignKeyMapper;
import ai.chat2db.server.domain.repository.mapper.VirtualForeignKeyMapper;
import ai.chat2db.server.tools.base.excption.BusinessException;
import ai.chat2db.server.tools.base.wrapper.result.ActionResult;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.tools.common.util.ContextUtils;
import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.SqlBuilder;
import ai.chat2db.spi.model.ForeignKey;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.model.TableColumn;
import ai.chat2db.spi.model.VirtualForeignKey;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ForeignKeySyncServiceImpl implements ForeignKeySyncService {

    private static final String SOURCE_TYPE_REAL = "REAL";
    private static final String SOURCE_TYPE_VIRTUAL_MANUAL = "MANUAL";
    private static final String SOURCE_TYPE_VIRTUAL_INFERRED = "INFERRED";

    private ForeignKeyMapper getFKMapper() {
        return Dbutils.getMapper(ForeignKeyMapper.class);
    }

    private VirtualForeignKeyMapper getVFKMapper() {
        return Dbutils.getMapper(VirtualForeignKeyMapper.class);
    }

    /**
     * 同步数据库表的外键定义到本地H2数据库
     * 该方法执行以下操作：
     * 1. 从数据库元数据中获取当前表的所有外键定义
     * 2. 从本地H2数据库查询已存储的外键记录
     * 3. 比较差异：添加数据库中存在但本地不存在的外键
     * 4. 删除本地存在但数据库中已不存在的外键
     * 5. 更新同步时间戳和版本号
     *
     * @param dataSourceId 数据源ID
     * @param databaseName 数据库名称（可为空）
     * @param schemaName   数据库模式名称（可为空）
     * @param tableName    表名称
     * @return SyncResult 同步结果对象，包含新增、删除和保留的外键数量
     */
    @Override
    public SyncResult syncForeignKeys(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        try {
            Connection connection = Chat2DBContext.getConnection();
            MetaData metaData = Chat2DBContext.getMetaData();
            List<ForeignKey> dbForeignKeys = metaData.foreignKeys(connection, databaseName, schemaName, tableName);

            List<ForeignKeyDO> existingFKs = queryRealFKsFromH2(dataSourceId, databaseName, schemaName, tableName);

            Set<String> dbFKKeys = dbForeignKeys.stream()
                    .map(this::buildUniqueKey)
                    .collect(Collectors.toSet());
            Set<String> existingFKKeys = existingFKs.stream()
                    .map(this::buildUniqueKeyFromDO)
                    .collect(Collectors.toSet());

            int added = 0, deleted = 0;

            for (ForeignKey fk : dbForeignKeys) {
                if (!existingFKKeys.contains(buildUniqueKey(fk))) {
                    insertForeignKey(fk, dataSourceId);
                    added++;
                }
            }

            for (ForeignKeyDO existing : existingFKs) {
                if (!dbFKKeys.contains(buildUniqueKeyFromDO(existing))) {
                    LambdaUpdateWrapper<ForeignKeyDO> wrapper = new LambdaUpdateWrapper<>();
                    wrapper.eq(ForeignKeyDO::getId, existing.getId());
                    getFKMapper().delete(wrapper);
                    deleted++;
                }
            }

            String syncVersion = UUID.randomUUID().toString().substring(0, 8);
            LocalDateTime now = LocalDateTime.now();
            LambdaUpdateWrapper<ForeignKeyDO> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(ForeignKeyDO::getDataSourceId, dataSourceId)
                    .eq(StringUtils.isNotBlank(databaseName), ForeignKeyDO::getDatabaseName, databaseName)
                    .eq(StringUtils.isNotBlank(schemaName), ForeignKeyDO::getSchemaName, schemaName)
                    .eq(StringUtils.isNotBlank(tableName), ForeignKeyDO::getTableName, tableName);
            ForeignKeyDO updateDO = new ForeignKeyDO();
            updateDO.setSyncTime(now);
            updateDO.setSyncVersion(syncVersion);
            getFKMapper().update(updateDO, updateWrapper);

            return new SyncResult(added, deleted, dbForeignKeys.size() - added);
        } catch (Exception e) {
            log.error("syncForeignKeys error", e);
            return new SyncResult(0, 0, 0);
        }
    }

    /**
     * 查询指定表的所有外键（包括真实外键和虚拟外键）
     * 该方法合并查询本地H2数据库中存储的真实外键和用户定义的虚拟外键
     *
     * @param dataSourceId 数据源ID
     * @param databaseName 数据库名称（可为空）
     * @param schemaName   数据库模式名称（可为空）
     * @param tableName    表名称
     * @return List<ForeignKey> 包含所有外键的列表
     */
    @Override
    public List<ForeignKey> listAllForeignKeys(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        List<ForeignKey> result = new ArrayList<>();

        List<ForeignKeyDO> realFKs = queryRealFKsFromH2(dataSourceId, databaseName, schemaName, tableName);
        for (ForeignKeyDO fk : realFKs) {
            result.add(convertDOToModel(fk));
        }

        List<VirtualForeignKeyDO> virtualFKs = queryVirtualFKsFromH2(dataSourceId, databaseName, schemaName, tableName);
        for (VirtualForeignKeyDO vk : virtualFKs) {
            result.add(convertVirtualDOToModel(vk));
        }

        return result;
    }

    /**
     * 创建虚拟外键
     * 虚拟外键是用户手动定义的逻辑外键关系，不实际存在于数据库中
     * 该方法会检查虚拟外键是否已存在，避免重复创建
     *
     * @param param 创建虚拟外键的参数对象
     * @return DataResult<VirtualForeignKey> 创建结果，包含成功或失败信息
     */
    @Override
    public VirtualForeignKey createVirtualFK(CreateVirtualFKParam param) {
        validateVirtualForeignKeyRelation(
                param.getDatabaseName(),
                param.getSchemaName(),
                param.getTableName(),
                param.getColumnName(),
                param.getReferencedTable(),
                param.getReferencedColumnName());

        if (existsVirtualForeignKey(null,
                param.getDataSourceId(),
                param.getDatabaseName(),
                param.getSchemaName(),
                param.getTableName(),
                param.getColumnName())) {
            throw new BusinessException("VIRTUAL_FK_EXISTS", new Object[]{});
        }

        VirtualForeignKeyDO entity = buildVirtualForeignKeyDO(param);
        getVFKMapper().insert(entity);
        return convertVirtualDOToModel(entity);
    }

    @Override
    public List<VirtualForeignKey> createInferredVirtualFKs(List<CreateVirtualFKParam> params) {
        if (CollectionUtils.isEmpty(params)) {
            return Collections.emptyList();
        }

        CreateVirtualFKParam first = params.get(0);
        List<VirtualForeignKeyDO> existingFKs = queryVirtualFKsFromH2(
                first.getDataSourceId(),
                first.getDatabaseName(),
                first.getSchemaName(),
                null);
        Set<String> existingKeys = existingFKs.stream()
                .map(vfk -> buildVirtualForeignKeyKey(
                        vfk.getDataSourceId(),
                        vfk.getDatabaseName(),
                        vfk.getSchemaName(),
                        vfk.getTableName(),
                        vfk.getColumnName()))
                .collect(Collectors.toSet());

        List<VirtualForeignKey> created = new ArrayList<>();
        for (CreateVirtualFKParam param : params) {
            String key = buildVirtualForeignKeyKey(
                    param.getDataSourceId(),
                    param.getDatabaseName(),
                    param.getSchemaName(),
                    param.getTableName(),
                    param.getColumnName());
            if (!existingKeys.add(key)) {
                continue;
            }

            VirtualForeignKeyDO entity = buildVirtualForeignKeyDO(param);
            getVFKMapper().insert(entity);
            created.add(convertVirtualDOToModel(entity));
        }
        return created;
    }

    /**
     * 更新虚拟外键信息
     * 该方法支持更新虚拟外键的注释、引用表、引用列和名称等属性
     *
     * @param param 更新虚拟外键的参数对象
     * @return DataResult<VirtualForeignKey> 更新结果，包含成功或失败信息
     */
    @Override
    public VirtualForeignKey updateVirtualFK(UpdateVirtualFKParam param) {
        VirtualForeignKeyDO existing = getVFKMapper().selectById(param.getId());
        if (existing == null) {
            throw new BusinessException("VIRTUAL_FK_NOT_FOUND", new Object[]{"虚拟外键不存在"});
        }

        VirtualForeignKeyDO updateDO = new VirtualForeignKeyDO();
        updateDO.setId(param.getId());
        String tableName = StringUtils.defaultIfBlank(param.getTableName(), existing.getTableName());
        String columnName = StringUtils.defaultIfBlank(param.getColumnName(), existing.getColumnName());
        String referencedTable = StringUtils.defaultIfBlank(param.getReferencedTable(), existing.getReferencedTable());
        String referencedColumnName = StringUtils.defaultIfBlank(
                param.getReferencedColumnName(), existing.getReferencedColumnName());

        validateVirtualForeignKeyRelation(
                existing.getDatabaseName(),
                existing.getSchemaName(),
                tableName,
                columnName,
                referencedTable,
                referencedColumnName);

        if (existsVirtualForeignKey(param.getId(),
                existing.getDataSourceId(),
                existing.getDatabaseName(),
                existing.getSchemaName(),
                tableName,
                columnName)) {
            throw new BusinessException("VIRTUAL_FK_EXISTS", new Object[]{});
        }

        updateDO.setComment(param.getComment());
        if (StringUtils.isNotBlank(param.getTableName())) {
            updateDO.setTableName(param.getTableName());
        }
        if (StringUtils.isNotBlank(param.getColumnName())) {
            updateDO.setColumnName(param.getColumnName());
        }
        if (StringUtils.isNotBlank(param.getReferencedTable())) {
            updateDO.setReferencedTable(param.getReferencedTable());
        }
        if (StringUtils.isNotBlank(param.getReferencedColumnName())) {
            updateDO.setReferencedColumnName(param.getReferencedColumnName());
        }
        if (StringUtils.isNotBlank(param.getVkName())) {
            updateDO.setVkName(param.getVkName());
        }

        getVFKMapper().updateById(updateDO);

        VirtualForeignKeyDO updated = getVFKMapper().selectById(param.getId());
        return VirtualForeignKey.builder()
                .name(updated.getVkName())
                .tableName(updated.getTableName())
                .column(updated.getColumnName())
                .referencedTable(updated.getReferencedTable())
                .referencedColumn(updated.getReferencedColumnName())
                .comment(updated.getComment())
                .virtualProperty("User-defined virtual foreign key")
                .build();
    }

    /**
     * 删除虚拟外键
     * 该方法根据ID删除指定的虚拟外键记录
     *
     * @param id 虚拟外键的ID
     */
    @Override
    public void deleteVirtualFK(Long id) {
        VirtualForeignKeyDO existing = getVFKMapper().selectById(id);
        if (existing == null) {
            throw new BusinessException("VIRTUAL_FK_NOT_FOUND", new Object[]{"虚拟外键不存在"});
        }
        getVFKMapper().deleteById(id);
    }

    /**
     * 删除真实外键（物理外键）
     * 该方法不仅从本地H2数据库删除外键记录，还会生成对应的DROP FOREIGN KEY SQL语句
     *
     * @param id 真实外键的ID
     * @return String DROP SQL语句
     */
    @Override
    public String deleteRealFK(Long id) {
        ForeignKeyDO existing = getFKMapper().selectById(id);
        if (existing == null) {
            throw new BusinessException("FK_NOT_FOUND", new Object[]{"外键不存在"});
        }

        SqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        ForeignKey fk = ForeignKey.builder()
                .name(existing.getFkName())
                .tableName(existing.getTableName())
                .column(existing.getColumnName())
                .referencedTable(existing.getReferencedTable())
                .referencedColumn(existing.getReferencedColumnName())
                .updateRule(existing.getUpdateRule() != null ? existing.getUpdateRule() : 0)
                .deleteRule(existing.getDeleteRule() != null ? existing.getDeleteRule() : 0)
                .build();

        String dropFKSql = sqlBuilder.buildDropForeignKeySql(fk);

        getFKMapper().deleteById(id);

        return dropFKSql;
    }

    /**
     * 生成外键的DDL语句（CREATE/ALTER/DROP）
     * 该方法比较新旧表结构，生成必要的外键变更SQL语句
     *
     * @param oldTable 旧表结构（可为空）
     * @param newTable 新表结构（可为空）
     * @return List<String> 包含所有需要执行的DDL语句的列表
     */
    @Override
    public List<String> generateForeignKeyDDL(Table oldTable, Table newTable) {
        List<String> ddlList = new ArrayList<>();

        List<ForeignKey> oldFKs = oldTable != null && oldTable.getForeignKeyList() != null
                ? oldTable.getForeignKeyList() : Collections.emptyList();
        List<ForeignKey> newFKs = newTable != null && newTable.getForeignKeyList() != null
                ? newTable.getForeignKeyList() : Collections.emptyList();

        Map<String, ForeignKey> oldFKMap = oldFKs.stream()
                .collect(Collectors.toMap(this::buildUniqueKey, f -> f, (o1, o2) -> o1));
        Map<String, ForeignKey> newFKMap = newFKs.stream()
                .collect(Collectors.toMap(this::buildUniqueKey, f -> f, (o1, o2) -> o1));

        SqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();

        for (ForeignKey newFK : newFKs) {
            if (!oldFKMap.containsKey(buildUniqueKey(newFK))) {
                String ddl = sqlBuilder.buildAddForeignKeySql(newFK);
                if (StringUtils.isNotBlank(ddl)) {
                    ddlList.add(ddl);
                }
            }
        }

        for (ForeignKey oldFK : oldFKs) {
            if (!newFKMap.containsKey(buildUniqueKey(oldFK))) {
                String ddl = sqlBuilder.buildDropForeignKeySql(oldFK);
                if (StringUtils.isNotBlank(ddl)) {
                    ddlList.add(ddl);
                }
            }
        }

        return ddlList;
    }

    /**
     * 查询指定表的真实外键（物理外键）
     * 该方法仅查询数据库中实际存在的外键，不包含虚拟外键
     *
     * @param dataSourceId 数据源ID
     * @param databaseName 数据库名称（可为空）
     * @param schemaName   数据库模式名称（可为空）
     * @param tableName    表名称
     * @return List<ForeignKey> 真实外键列表
     */
    @Override
    public List<ForeignKey> queryRealForeignKeys(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        List<ForeignKeyDO> doList = queryRealFKsFromH2(dataSourceId, databaseName, schemaName, tableName);
        return doList.stream()
                .map(this::convertDOToModel)
                .collect(Collectors.toList());
    }

    /**
     * 查询指定表的虚拟外键
     * 该方法查询用户手动定义的虚拟外键，不包含数据库中真实存在的外键
     *
     * @param dataSourceId 数据源ID
     * @param databaseName 数据库名称（可为空）
     * @param schemaName   数据库模式名称（可为空）
     * @param tableName    表名称
     * @return List<VirtualForeignKey> 虚拟外键列表
     */
    @Override
    public List<VirtualForeignKey> queryVirtualForeignKeys(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        List<VirtualForeignKeyDO> doList = queryVirtualFKsFromH2(dataSourceId, databaseName, schemaName, tableName);
        return doList.stream()
                .map(this::convertVirtualDOToModel)
                .collect(Collectors.toList());
    }

    @Override
    public List<VirtualForeignKey> queryAllVirtualForeignKeys(Long dataSourceId, String databaseName, String schemaName) {
        List<VirtualForeignKeyDO> doList = queryVirtualFKsFromH2(dataSourceId, databaseName, schemaName, null);
        return doList.stream()
                .map(this::convertVirtualDOToModel)
                .collect(Collectors.toList());
    }

    @Override
    public int cleanInvalidVirtualForeignKeys(Long dataSourceId, String databaseName, String schemaName, List<String> existingTableNames) {
        if (CollectionUtils.isEmpty(existingTableNames)) {
            return 0;
        }

        Set<String> existingTableSet = existingTableNames.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        LambdaQueryWrapper<VirtualForeignKeyDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VirtualForeignKeyDO::getDataSourceId, dataSourceId)
                .eq(StringUtils.isNotBlank(databaseName), VirtualForeignKeyDO::getDatabaseName, databaseName)
                .eq(StringUtils.isNotBlank(schemaName), VirtualForeignKeyDO::getSchemaName, schemaName);

        List<VirtualForeignKeyDO> allVirtualFKs = getVFKMapper().selectList(wrapper);
        if (CollectionUtils.isEmpty(allVirtualFKs)) {
            return 0;
        }

        List<Long> idsToDelete = new ArrayList<>();
        for (VirtualForeignKeyDO vfk : allVirtualFKs) {
            boolean tableExists = existingTableSet.contains(vfk.getTableName().toLowerCase());
            boolean referencedTableExists = existingTableSet.contains(vfk.getReferencedTable().toLowerCase());

            if (!tableExists || !referencedTableExists) {
                idsToDelete.add(vfk.getId());
            }
        }

        if (!idsToDelete.isEmpty()) {
            getVFKMapper().deleteBatchIds(idsToDelete);
            log.info("Cleaned {} invalid virtual foreign keys for dataSourceId={}, databaseName={}, schemaName={}",
                    idsToDelete.size(), dataSourceId, databaseName, schemaName);
        }

        return idsToDelete.size();
    }

    /**
     * 从H2数据库查询真实外键记录
     * 根据数据源ID和表信息查询本地存储的真实外键
     *
     * @param dataSourceId 数据源ID
     * @param databaseName 数据库名称（可为空）
     * @param schemaName   数据库模式名称（可为空）
     * @param tableName    表名称
     * @return List<ForeignKeyDO> 真实外键实体列表
     */
    private List<ForeignKeyDO> queryRealFKsFromH2(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        LambdaQueryWrapper<ForeignKeyDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ForeignKeyDO::getDataSourceId, dataSourceId)
                .eq(StringUtils.isNotBlank(tableName), ForeignKeyDO::getTableName, tableName)
                .eq(StringUtils.isNotBlank(databaseName), ForeignKeyDO::getDatabaseName, databaseName)
                .eq(StringUtils.isNotBlank(schemaName), ForeignKeyDO::getSchemaName, schemaName);
        return getFKMapper().selectList(wrapper);
    }

    /**
     * 从H2数据库查询虚拟外键记录
     * 根据数据源ID和表信息查询本地存储的虚拟外键
     *
     * @param dataSourceId 数据源ID
     * @param databaseName 数据库名称（可为空）
     * @param schemaName   数据库模式名称（可为空）
     * @param tableName    表名称
     * @return List<VirtualForeignKeyDO> 虚拟外键实体列表
     */
    private List<VirtualForeignKeyDO> queryVirtualFKsFromH2(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        LambdaQueryWrapper<VirtualForeignKeyDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VirtualForeignKeyDO::getDataSourceId, dataSourceId)
                .eq(StringUtils.isNotBlank(tableName), VirtualForeignKeyDO::getTableName, tableName)
                .eq(StringUtils.isNotBlank(databaseName), VirtualForeignKeyDO::getDatabaseName, databaseName)
                .eq(StringUtils.isNotBlank(schemaName), VirtualForeignKeyDO::getSchemaName, schemaName);
        return getVFKMapper().selectList(wrapper);
    }

    private void validateVirtualForeignKeyRelation(String databaseName,
                                                   String schemaName,
                                                   String tableName,
                                                   String columnName,
                                                   String referencedTable,
                                                   String referencedColumnName) {
        MetaData metaData = Chat2DBContext.getMetaData();
        Connection connection = Chat2DBContext.getConnection();

        String actualTableName = findActualTableName(metaData, connection, databaseName, schemaName, tableName);
        String actualReferencedTable = findActualTableName(
                metaData, connection, databaseName, schemaName, referencedTable);

        if (StringUtils.isBlank(actualTableName) || StringUtils.isBlank(actualReferencedTable)) {
            throw new BusinessException("VIRTUAL_FK_TABLE_NOT_FOUND", new Object[]{"虚拟外键关联表不存在"});
        }

        if (!existsColumnIgnoreCase(metaData, connection, databaseName, schemaName, actualTableName, columnName)
                || !existsColumnIgnoreCase(
                metaData, connection, databaseName, schemaName, actualReferencedTable, referencedColumnName)) {
            throw new BusinessException("VIRTUAL_FK_COLUMN_NOT_FOUND", new Object[]{"虚拟外键关联字段不存在"});
        }
    }

    private String findActualTableName(MetaData metaData,
                                       Connection connection,
                                       String databaseName,
                                       String schemaName,
                                       String tableName) {
        if (StringUtils.isBlank(tableName)) {
            return null;
        }
        List<Table> tables = metaData.tables(connection, databaseName, schemaName, null);
        return Optional.ofNullable(tables).orElse(Collections.emptyList()).stream()
                .map(Table::getName)
                .filter(name -> StringUtils.equalsIgnoreCase(name, tableName))
                .findFirst()
                .orElse(null);
    }

    private boolean existsColumnIgnoreCase(MetaData metaData,
                                           Connection connection,
                                           String databaseName,
                                           String schemaName,
                                           String tableName,
                                           String columnName) {
        if (StringUtils.isAnyBlank(tableName, columnName)) {
            return false;
        }
        List<TableColumn> columns = metaData.columns(connection, databaseName, schemaName, tableName);
        return Optional.ofNullable(columns).orElse(Collections.emptyList()).stream()
                .map(TableColumn::getName)
                .anyMatch(name -> StringUtils.equalsIgnoreCase(name, columnName));
    }

    private boolean existsVirtualForeignKey(Long excludeId,
                                            Long dataSourceId,
                                            String databaseName,
                                            String schemaName,
                                            String tableName,
                                            String columnName) {
        LambdaQueryWrapper<VirtualForeignKeyDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VirtualForeignKeyDO::getDataSourceId, dataSourceId)
                .eq(StringUtils.isNotBlank(databaseName), VirtualForeignKeyDO::getDatabaseName, databaseName)
                .and(StringUtils.isBlank(databaseName), item -> item
                        .isNull(VirtualForeignKeyDO::getDatabaseName)
                        .or()
                        .eq(VirtualForeignKeyDO::getDatabaseName, ""))
                .eq(StringUtils.isNotBlank(schemaName), VirtualForeignKeyDO::getSchemaName, schemaName)
                .and(StringUtils.isBlank(schemaName), item -> item
                        .isNull(VirtualForeignKeyDO::getSchemaName)
                        .or()
                        .eq(VirtualForeignKeyDO::getSchemaName, ""))
                .eq(VirtualForeignKeyDO::getTableName, tableName)
                .eq(VirtualForeignKeyDO::getColumnName, columnName)
                .ne(excludeId != null, VirtualForeignKeyDO::getId, excludeId);
        return getVFKMapper().selectCount(wrapper) > 0;
    }

    private VirtualForeignKeyDO buildVirtualForeignKeyDO(CreateVirtualFKParam param) {
        VirtualForeignKeyDO entity = new VirtualForeignKeyDO();
        entity.setDataSourceId(param.getDataSourceId());
        entity.setDatabaseName(param.getDatabaseName());
        entity.setSchemaName(param.getSchemaName());
        entity.setTableName(param.getTableName());
        entity.setColumnName(param.getColumnName());
        entity.setVkName("VFK_" + param.getTableName() + "_" + param.getColumnName());
        entity.setReferencedTable(param.getReferencedTable());
        entity.setReferencedColumnName(param.getReferencedColumnName());
        entity.setComment(param.getComment());
        entity.setSourceType(param.getSourceType());
        entity.setUserId(ContextUtils.getUserId());
        return entity;
    }

    private String buildVirtualForeignKeyKey(Long dataSourceId,
                                             String databaseName,
                                             String schemaName,
                                             String tableName,
                                             String columnName) {
        return String.join(":",
                Objects.toString(dataSourceId, ""),
                normalizeVirtualForeignKeyPart(databaseName),
                normalizeVirtualForeignKeyPart(schemaName),
                normalizeVirtualForeignKeyPart(tableName),
                normalizeVirtualForeignKeyPart(columnName));
    }

    private String normalizeVirtualForeignKeyPart(String value) {
        return StringUtils.defaultString(value).toLowerCase(Locale.ROOT);
    }

    /**
     * 将外键信息插入到H2数据库
     * 将从数据库元数据获取的外键信息转换为实体并保存到本地H2数据库
     *
     * @param fk           外键模型对象
     * @param dataSourceId 数据源ID
     */
    private void insertForeignKey(ForeignKey fk, Long dataSourceId) {
        ForeignKeyDO entity = new ForeignKeyDO();
        entity.setDataSourceId(dataSourceId);
        entity.setDatabaseName(fk.getDatabaseName());
        entity.setSchemaName(fk.getSchemaName());
        entity.setTableName(fk.getTableName());
        entity.setColumnName(fk.getColumn());
        entity.setFkName(fk.getName());
        entity.setReferencedTable(fk.getReferencedTable());
        entity.setReferencedColumnName(fk.getReferencedColumn());
        entity.setReferencedSchema(fk.getSchemaName());
        entity.setReferencedDatabase(fk.getDatabaseName());
        entity.setUpdateRule(fk.getUpdateRule());
        entity.setDeleteRule(fk.getDeleteRule());
        entity.setComment(fk.getComment());
        entity.setSyncTime(LocalDateTime.now());
        getFKMapper().insert(entity);
    }

    private ForeignKey convertDOToModel(ForeignKeyDO fk) {
        return ForeignKey.builder()
                .id(fk.getId())
                .name(fk.getFkName())
                .tableName(fk.getTableName())
                .schemaName(fk.getSchemaName())
                .databaseName(fk.getDatabaseName())
                .column(fk.getColumnName())
                .referencedTable(fk.getReferencedTable())
                .referencedColumn(fk.getReferencedColumnName())
                .updateRule(fk.getUpdateRule() != null ? fk.getUpdateRule() : 0)
                .deleteRule(fk.getDeleteRule() != null ? fk.getDeleteRule() : 0)
                .comment(fk.getComment())
                .build();
    }

    private VirtualForeignKey convertVirtualDOToModel(VirtualForeignKeyDO vk) {
        return VirtualForeignKey.builder()
                .id(vk.getId())
                .name(vk.getVkName())
                .tableName(vk.getTableName())
                .schemaName(vk.getSchemaName())
                .databaseName(vk.getDatabaseName())
                .column(vk.getColumnName())
                .referencedTable(vk.getReferencedTable())
                .referencedColumn(vk.getReferencedColumnName())
                .comment(vk.getComment())
                .virtualProperty(vk.getSourceType() != null ? vk.getSourceType() : "User-defined virtual foreign key")
                .build();
    }

    private String buildUniqueKey(ForeignKey fk) {
        return String.join(":",
                StringUtils.defaultString(fk.getTableName()),
                StringUtils.defaultString(fk.getColumn()),
                StringUtils.defaultString(fk.getReferencedTable()),
                StringUtils.defaultString(fk.getReferencedColumn())
        );
    }

    private String buildUniqueKeyFromDO(ForeignKeyDO fk) {
        return String.join(":",
                StringUtils.defaultString(fk.getTableName()),
                StringUtils.defaultString(fk.getColumnName()),
                StringUtils.defaultString(fk.getReferencedTable()),
                StringUtils.defaultString(fk.getReferencedColumnName())
        );
    }
}

