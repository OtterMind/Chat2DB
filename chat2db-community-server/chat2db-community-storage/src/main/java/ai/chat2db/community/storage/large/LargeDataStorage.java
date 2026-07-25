package ai.chat2db.community.storage.large;

import ai.chat2db.community.domain.api.converter.LocalStorageConverter;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceLocalStorage;
import ai.chat2db.community.storage.IdUtil;
import ai.chat2db.community.tools.util.ConfigUtils;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

@Slf4j
public class LargeDataStorage<T> implements IWorkspaceLocalStorage<T> {

    private static final String DB_STORAGE_PATH = ConfigUtils.getEnvBasePath()
            + File.separator + "storage";

    private ConcurrentSkipListMap<Long, T> dataMap = new ConcurrentSkipListMap<>();


    private String storageDir;

    private String filePath;

    private String name;

    private int limit;

    protected LargeDataStorage(String name, Class<T> clazz, int limit) {
        this(name, clazz, limit, DB_STORAGE_PATH);
    }

    protected LargeDataStorage(String name, Class<T> clazz, int limit, String storageBasePath) {
        this.storageDir = storageBasePath + File.separator + name;
        this.filePath = storageDir + File.separator + name + ".json";
        this.limit = limit;
        this.name = name;
        if (!FileUtil.exist(filePath)) {
            FileUtil.writeUtf8String("", filePath);
        } else {
            FileUtil.readLines(filePath, "UTF-8").forEach(line -> {
                if (StringUtils.isNotBlank(line)) {
                    try {
                        Long id = Long.parseLong(line.trim());
                        String detail = FileUtil.readUtf8String(detailFilePath(id));
                        if (StringUtils.isNotBlank(detail)) {
                            T t = JSON.parseObject(detail, clazz);
                            dataMap.put(id, t);
                        }
                    } catch (Exception e) {
                        log.error("LargeDataStorage error", e);
                    }
                }
            });
        }
    }

    private String detailFilePath(Long id) {
        return storageDir + File.separator + id + ".json";
    }

    @Override
    public List<T> getDataList() {
        return Lists.newArrayList(dataMap.values());
    }


    @Override
    public T getById(Long id) {
        if (id == null) {
            return null;
        }
        return dataMap.get(id);
    }

    @Override
    public synchronized Long save(T data) {
        if (data == null) {
            return null;
        }
        try {
            if (dataMap.size() >= limit) {
                Map.Entry<Long, T> entry = dataMap.pollFirstEntry();
                if (entry != null) {
                    saveDataList();
                    deleteDetailData(entry.getKey());
                }
            }
            Long id = LocalStorageConverter.ensureId(data, this::generateId);

            dataMap.put(id, data);
            FileUtil.appendUtf8String(id + "\n", filePath);
            saveDetailData(id, data);

            return id;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void saveDetailData(Long id, T data) {
        if (data == null) {
            return;
        }
        if (id == null) {
            return;
        }
        try {
            FileUtil.writeUtf8String(JSON.toJSONString(data), detailFilePath(id));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public synchronized void update(T data) {
        if (data == null) {
            return;
        }
        try {
            Long id = LocalStorageConverter.getId(data);
            if (id == null) {
                return;
            }
            T before = dataMap.get(id);
            if (before == null) {
                return;
            }
            before = getAfterSave(before, data);
            saveDetailData(id, before);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void delete(Long id) {
        if (id == null) {
            return;
        }
        dataMap.remove(id);
        saveDataList();
        deleteDetailData(id);
    }

    protected void deleteDetailData(Long id) {
        if (id == null) {
            return;
        }
        try {
            FileUtil.del(detailFilePath(id));
        } catch (Exception e) {
            log.error("deleteDetailData error", e);
        }
    }

    protected void saveDataList() {
        try {
            List<Long> dataList = dataMap.keySet().stream().toList();
            if (CollectionUtils.isNotEmpty(dataList)) {
                String data = dataList.stream().map(String::valueOf).collect(Collectors.joining("\n"));
                FileUtil.writeUtf8String(data + "\n", filePath);
            } else {
                FileUtil.writeUtf8String("", filePath);
            }
        } catch (Exception e) {
            log.error("saveDataList error", e);
        }
    }

    public Long generateId() {
        return IdUtil.generateId();
    }
}
