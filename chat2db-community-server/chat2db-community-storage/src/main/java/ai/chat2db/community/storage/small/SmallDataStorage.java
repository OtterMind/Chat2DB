package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.converter.LocalStorageConverter;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceLocalStorage;
import ai.chat2db.community.storage.IdUtil;
import ai.chat2db.community.tools.util.ConfigUtils;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

@Slf4j
public class SmallDataStorage<T> implements IWorkspaceLocalStorage<T> {

    protected static final String DB_STORAGE_PATH = ConfigUtils.getEnvBasePath() + File.separator + "storage";

    protected Map<Long, T> dataMap = new ConcurrentSkipListMap<>();

    protected String filePath;

    protected SmallDataStorage(String name, Class<T> clazz) {
        this(new File(DB_STORAGE_PATH + File.separator + name + File.separator + name + ".json"), clazz);
    }

    protected SmallDataStorage(File storageFile, Class<T> clazz) {
        this.filePath = storageFile.getAbsolutePath();
        if (!FileUtil.exist(filePath)) {
            FileUtil.writeUtf8String("", filePath);
        } else {
            FileUtil.readLines(filePath, "UTF-8").forEach(line -> {
                if (StringUtils.isNotBlank(line)) {
                    try {
                        T t = JSON.parseObject(line.trim(), clazz);
                        Long id = LocalStorageConverter.getId(t);
                        dataMap.put(id, t);
                    } catch (Exception e) {
                        log.error("SmallDataStorage error", e);
                    }
                }
            });
        }
    }

    public static <T> SmallDataStorage<T> create(String name, Class<T> clazz) {
        return new SmallDataStorage<>(name, clazz);
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
            Long id = LocalStorageConverter.ensureId(data, this::generateId);
            if (dataMap.get(id) != null) {
                dataMap.put(id, data);
                saveDataList();
            } else {
                dataMap.put(id, data);
                FileUtil.appendUtf8String(JSON.toJSONString(data) + "\n", filePath);
            }
            return id;
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
            dataMap.put(id, before);
            saveDataList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public synchronized void delete(Long id) {
        dataMap.remove(id);
        saveDataList();
    }

    protected synchronized void saveDataList() {
        List<T> dataList = getDataList();
        StringBuilder content = new StringBuilder();
        for (T data : dataList) {
            content.append(JSON.toJSONString(data)).append('\n');
        }

        Path target = Path.of(filePath).toAbsolutePath();
        Path parent = target.getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            replaceStorageFile(temp, target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist storage file: " + target, e);
        } finally {
            deleteTempFile(temp);
        }
    }

    protected void replaceStorageFile(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTempFile(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Failed to clean up temporary storage file: {}", temp, e);
        }
    }

    public Long generateId() {
        return IdUtil.generateId();
    }

}
