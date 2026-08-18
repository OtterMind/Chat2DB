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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

@Slf4j
public class LargeDataStorage<T> implements IWorkspaceLocalStorage<T> {

    private static final String DB_STORAGE_PATH = ConfigUtils.getEnvBasePath()
            + File.separator + "storage";

    protected final ConcurrentSkipListMap<Long, T> dataMap = new ConcurrentSkipListMap<>();


    protected final String storageDir;

    protected final String filePath;

    private final int limit;

    protected LargeDataStorage(String name, Class<T> clazz, int limit) {
        this(name, clazz, limit, DB_STORAGE_PATH);
    }

    protected LargeDataStorage(String name, Class<T> clazz, int limit, String storageBasePath) {
        this(name, name, clazz, limit, storageBasePath);
    }

    protected LargeDataStorage(String storageDirectoryName, String indexName, Class<T> clazz, int limit) {
        this(storageDirectoryName, indexName, clazz, limit, DB_STORAGE_PATH);
    }

    protected LargeDataStorage(String storageDirectoryName, String indexName, Class<T> clazz, int limit,
            String storageBasePath) {
        this.storageDir = storageBasePath + File.separator + storageDirectoryName;
        this.filePath = storageDir + File.separator + indexName + ".json";
        this.limit = limit;
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

    protected String detailFilePath(Long id) {
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
            if (limit > 0 && dataMap.size() >= limit) {
                Map.Entry<Long, T> entry = dataMap.pollFirstEntry();
                if (entry != null) {
                    saveDataList();
                    deleteDetailData(entry.getKey());
                }
            }
            Long id = LocalStorageConverter.ensureId(data, this::generateId);

            saveDetailData(id, data);
            if (!dataMap.containsKey(id)) {
                FileUtil.appendUtf8String(id + "\n", filePath);
            }
            dataMap.put(id, data);

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
            writeUtf8Atomically(Path.of(detailFilePath(id)), JSON.toJSONString(data));
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
            saveDataListOrThrow();
        } catch (Exception e) {
            log.error("saveDataList error", e);
        }
    }

    protected void saveDataListOrThrow() {
        List<Long> dataList = dataMap.keySet().stream().toList();
        String data = CollectionUtils.isNotEmpty(dataList)
                ? dataList.stream().map(String::valueOf).collect(Collectors.joining("\n")) + "\n"
                : "";
        try {
            writeUtf8Atomically(Path.of(filePath), data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Long generateId() {
        return IdUtil.generateId();
    }

    protected synchronized void replaceData(Long id, T data) {
        if (id == null || data == null || !dataMap.containsKey(id)) {
            return;
        }
        saveDetailData(id, data);
        dataMap.put(id, data);
    }

    protected synchronized void upsertDataStrict(Long id, T data) {
        if (id == null || data == null) {
            throw new IllegalArgumentException("id and data are required");
        }
        T previous = dataMap.get(id);
        saveDetailData(id, data);
        dataMap.put(id, data);
        if (previous != null) {
            return;
        }
        try {
            saveDataListOrThrow();
        } catch (RuntimeException e) {
            dataMap.remove(id);
            deleteDetailData(id);
            throw e;
        }
    }

    protected synchronized T removeDataStrict(Long id) {
        if (id == null) {
            return null;
        }
        T removed = dataMap.remove(id);
        if (removed == null) {
            return null;
        }
        try {
            saveDataListOrThrow();
            Files.deleteIfExists(Path.of(detailFilePath(id)));
            return removed;
        } catch (Exception e) {
            dataMap.put(id, removed);
            try {
                saveDetailData(id, removed);
                saveDataListOrThrow();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e instanceof RuntimeException runtimeException
                    ? runtimeException : new RuntimeException(e);
        }
    }

    protected static void writeUtf8Atomically(Path target, String content) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path directory = absoluteTarget.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = absoluteTarget.resolveSibling(
                "." + absoluteTarget.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, absoluteTarget, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
