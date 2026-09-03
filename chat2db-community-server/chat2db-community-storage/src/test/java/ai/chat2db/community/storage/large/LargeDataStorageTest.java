package ai.chat2db.community.storage.large;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeDataStorageTest {

    public static class Item {
        private Long id;
        private String value;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    static class TestStorage extends LargeDataStorage<Item> {
        TestStorage(String name, int limit, File baseDir) {
            super(name, Item.class, limit, baseDir.getAbsolutePath());
        }

        TestStorage(String directoryName, String indexName, int limit, File baseDir) {
            super(directoryName, indexName, Item.class, limit, baseDir.getAbsolutePath());
        }
    }

    static class FailingDetailStorage extends TestStorage {
        private boolean failWrites;

        FailingDetailStorage(String name, int limit, File baseDir) {
            super(name, limit, baseDir);
        }

        @Override
        protected void saveDetailData(Long id, Item data) {
            if (failWrites) {
                throw new IllegalStateException("simulated detail write failure");
            }
            super.saveDetailData(id, data);
        }
    }

    static class BlockingUpdateStorage extends TestStorage {
        private final CountDownLatch updateReadExisting = new CountDownLatch(1);
        private final CountDownLatch continueUpdate = new CountDownLatch(1);

        BlockingUpdateStorage(String name, int limit, File baseDir) {
            super(name, limit, baseDir);
        }

        @Override
        public Item getAfterSave(Item before, Item update) {
            updateReadExisting.countDown();
            try {
                assertTrue(continueUpdate.await(5, TimeUnit.SECONDS), "timed out waiting to continue update");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return super.getAfterSave(before, update);
        }
    }

    static class FailingDeleteStorage extends TestStorage {
        private boolean failIndexWrites;
        private boolean failCandidateIndexWrites;
        private boolean candidateDetailExistedAtFailure;
        private Long failedDetailDeleteId;

        FailingDeleteStorage(String name, int limit, File baseDir) {
            super(name, limit, baseDir);
        }

        @Override
        protected void saveDataListOrThrow() {
            if (failIndexWrites) {
                throw new IllegalStateException("simulated index write failure");
            }
            super.saveDataListOrThrow();
        }

        @Override
        protected void saveDataListOrThrow(Map<Long, Item> values) {
            if (failCandidateIndexWrites) {
                candidateDetailExistedAtFailure = values.keySet().stream()
                        .filter(id -> !dataMap.containsKey(id))
                        .anyMatch(id -> new File(detailFilePath(id)).isFile());
                throw new IllegalStateException("simulated candidate index write failure");
            }
            super.saveDataListOrThrow(values);
        }

        @Override
        protected void deleteDetailData(Long id) {
            if (id.equals(failedDetailDeleteId)) {
                throw new IllegalStateException("simulated detail delete failure");
            }
            super.deleteDetailData(id);
        }
    }

    private final String name = "test_large_data";

    /**
     * Storage root injected per test so no data is written under the real
     * user home; JUnit removes the directory afterwards.
     */
    @TempDir
    File baseDir;

    private File storageDir() {
        return new File(baseDir, name);
    }

    private File detailFile(Long id) {
        return new File(storageDir(), id + ".json");
    }

    private File indexFile() {
        return new File(storageDir(), name + ".json");
    }

    private Item item(String value) {
        Item item = new Item();
        item.setValue(value);
        return item;
    }

    /**
     * Explicit ids keep detail-file names deterministic for assertions.
     */
    private Item item(String value, long id) {
        Item item = item(value);
        item.setId(id);
        return item;
    }

    @Test
    void directoryAndIndexNamesCanDiffer() {
        TestStorage storage = new TestStorage("task-v2", "task", 0, baseDir);

        storage.save(item("value", 1L));

        File taskDirectory = new File(baseDir, "task-v2");
        assertTrue(new File(taskDirectory, "task.json").isFile());
        assertTrue(new File(taskDirectory, "1.json").isFile());
        assertFalse(new File(taskDirectory, "task-v2.json").exists());

        TestStorage reloaded = new TestStorage("task-v2", "task", 0, baseDir);
        assertEquals("value", reloaded.getById(1L).getValue());
    }

    @Test
    void zeroLimitKeepsEveryRecordAndReloadsThem() {
        TestStorage storage = new TestStorage(name, 0, baseDir);

        for (long id = 1; id <= 25; id++) {
            storage.save(item("value-" + id, id));
        }

        assertEquals(25, storage.getDataList().size());
        assertEquals(25, FileUtil.readLines(indexFile(), "UTF-8").size());
        assertEquals(25, new TestStorage(name, 0, baseDir).getDataList().size());
    }

    @Test
    void failedDetailWriteDoesNotPublishRecordInMemoryOrIndex() {
        FailingDetailStorage storage = new FailingDetailStorage(name, 0, baseDir);
        storage.failWrites = true;

        assertThrows(RuntimeException.class, () -> storage.save(item("value", 1L)));

        assertNull(storage.getById(1L));
        assertTrue(storage.getDataList().isEmpty());
        assertEquals("", FileUtil.readUtf8String(indexFile()));
    }

    @Test
    void failedDetailWriteAtLimitKeepsExistingRecords() {
        FailingDetailStorage storage = new FailingDetailStorage(name, 2, baseDir);
        storage.save(item("first", 1L));
        storage.save(item("second", 2L));
        storage.failWrites = true;

        assertThrows(RuntimeException.class, () -> storage.save(item("third", 3L)));

        assertEquals(List.of(1L, 2L), storage.getDataList().stream().map(Item::getId).toList());
        assertTrue(detailFile(1L).isFile());
        assertTrue(detailFile(2L).isFile());
        assertFalse(detailFile(3L).exists());
        assertEquals(List.of("1", "2"), FileUtil.readLines(indexFile(), "UTF-8"));
        assertEquals(List.of(1L, 2L), new TestStorage(name, 2, baseDir).getDataList().stream()
                .map(Item::getId).toList());
    }

    @Test
    void savingExistingRecordAtLimitDoesNotEvictAnotherRecord() {
        TestStorage storage = new TestStorage(name, 2, baseDir);
        storage.save(item("first", 1L));
        storage.save(item("second", 2L));

        storage.save(item("updated", 2L));

        assertEquals(List.of(1L, 2L), storage.getDataList().stream().map(Item::getId).toList());
        assertTrue(detailFile(1L).isFile());
        assertEquals("updated", storage.getById(2L).getValue());
        TestStorage reloaded = new TestStorage(name, 2, baseDir);
        assertEquals(List.of(1L, 2L), reloaded.getDataList().stream().map(Item::getId).toList());
        assertEquals("updated", reloaded.getById(2L).getValue());
    }

    @Test
    void failedCandidateIndexWriteCleansNewDetailAndKeepsExistingState() {
        FailingDeleteStorage storage = new FailingDeleteStorage(name, 10, baseDir);
        storage.save(item("before", 1L));
        String originalIndex = FileUtil.readUtf8String(indexFile());
        String originalDetail = FileUtil.readUtf8String(detailFile(1L));
        storage.failCandidateIndexWrites = true;

        assertThrows(RuntimeException.class, () -> storage.save(item("new", 2L)));

        assertTrue(storage.candidateDetailExistedAtFailure);
        assertEquals(List.of(1L), storage.getDataList().stream().map(Item::getId).toList());
        assertEquals(originalIndex, FileUtil.readUtf8String(indexFile()));
        assertEquals(originalDetail, FileUtil.readUtf8String(detailFile(1L)));
        assertFalse(detailFile(2L).exists());
        TestStorage reloaded = new TestStorage(name, 10, baseDir);
        assertEquals(List.of(1L), reloaded.getDataList().stream().map(Item::getId).toList());
        assertEquals("before", reloaded.getById(1L).getValue());
    }

    @Test
    void failedEvictionDetailDeleteRestoresExistingRecords() {
        FailingDeleteStorage storage = new FailingDeleteStorage(name, 2, baseDir);
        storage.save(item("first", 1L));
        storage.save(item("second", 2L));
        storage.failedDetailDeleteId = 1L;

        assertThrows(RuntimeException.class, () -> storage.save(item("third", 3L)));

        assertEquals(List.of(1L, 2L), storage.getDataList().stream().map(Item::getId).toList());
        assertTrue(detailFile(1L).isFile());
        assertTrue(detailFile(2L).isFile());
        assertFalse(detailFile(3L).exists());
        assertEquals(List.of("1", "2"), FileUtil.readLines(indexFile(), "UTF-8"));
        assertEquals(List.of(1L, 2L), new TestStorage(name, 2, baseDir).getDataList().stream()
                .map(Item::getId).toList());
    }

    @Test
    void failedUpdateKeepsMemoryDetailAndReloadState() {
        FailingDetailStorage storage = new FailingDetailStorage(name, 10, baseDir);
        storage.save(item("before", 1L));
        String originalDetail = FileUtil.readUtf8String(detailFile(1L));
        storage.failWrites = true;

        assertThrows(RuntimeException.class, () -> storage.update(item("after", 1L)));

        assertNotNull(storage.getById(1L));
        assertEquals("before", storage.getById(1L).getValue());
        assertEquals(originalDetail, FileUtil.readUtf8String(detailFile(1L)));
        assertEquals("before", new TestStorage(name, 10, baseDir).getById(1L).getValue());
    }

    @Test
    void failedDeleteIndexWriteRestoresMemoryDiskAndReloadState() {
        FailingDeleteStorage storage = new FailingDeleteStorage(name, 10, baseDir);
        storage.save(item("before", 1L));
        String originalIndex = FileUtil.readUtf8String(indexFile());
        String originalDetail = FileUtil.readUtf8String(detailFile(1L));
        storage.failIndexWrites = true;

        assertThrows(RuntimeException.class, () -> storage.delete(1L));

        assertNotNull(storage.getById(1L));
        assertEquals("before", storage.getById(1L).getValue());
        assertEquals(originalIndex, FileUtil.readUtf8String(indexFile()));
        assertEquals(originalDetail, FileUtil.readUtf8String(detailFile(1L)));
        assertEquals("before", new TestStorage(name, 10, baseDir).getById(1L).getValue());
    }

    @Test
    void failedDeleteDetailRestoresMemoryIndexAndReloadState() {
        FailingDeleteStorage storage = new FailingDeleteStorage(name, 10, baseDir);
        storage.save(item("before", 1L));
        String originalIndex = FileUtil.readUtf8String(indexFile());
        String originalDetail = FileUtil.readUtf8String(detailFile(1L));
        storage.failedDetailDeleteId = 1L;

        assertThrows(RuntimeException.class, () -> storage.delete(1L));

        assertNotNull(storage.getById(1L));
        assertEquals("before", storage.getById(1L).getValue());
        assertEquals(originalIndex, FileUtil.readUtf8String(indexFile()));
        assertEquals(originalDetail, FileUtil.readUtf8String(detailFile(1L)));
        assertEquals("before", new TestStorage(name, 10, baseDir).getById(1L).getValue());
    }

    @Test
    void deletedLastRecordMustNotResurrectAfterReload() {
        TestStorage storage = new TestStorage(name, 10, baseDir);
        Long id = storage.save(item("only"));

        storage.delete(id);

        assertEquals("", FileUtil.readUtf8String(indexFile()), "deleting the final record must empty the index");
        TestStorage reloaded = new TestStorage(name, 10, baseDir);
        assertTrue(reloaded.getDataList().isEmpty(), "deleted record must not reappear after reload");
    }

    @Test
    void deleteRemovesDetailFile() {
        TestStorage storage = new TestStorage(name, 10, baseDir);
        Long keptId = storage.save(item("kept", 1L));
        Long deletedId = storage.save(item("deleted", 2L));

        storage.delete(deletedId);

        assertFalse(detailFile(deletedId).exists(), "detail file of deleted record must be removed");
        assertTrue(detailFile(keptId).exists(), "detail file of remaining record must be kept");

        TestStorage reloaded = new TestStorage(name, 10, baseDir);
        List<Item> items = reloaded.getDataList();
        assertEquals(1, items.size());
        assertEquals("kept", items.get(0).getValue());
    }

    @Test
    void evictionRemovesDetailFileOfEvictedRecord() {
        TestStorage storage = new TestStorage(name, 2, baseDir);
        Long first = storage.save(item("first", 1L));
        storage.save(item("second", 2L));
        storage.save(item("third", 3L));

        assertFalse(detailFile(first).exists(), "detail file of evicted record must be removed");
        assertEquals(List.of("2", "3"), FileUtil.readLines(indexFile(), "UTF-8"),
                "the index must contain only surviving records");

        TestStorage reloaded = new TestStorage(name, 2, baseDir);
        assertEquals(2, reloaded.getDataList().size());
        assertTrue(reloaded.getDataList().stream().noneMatch(i -> "first".equals(i.getValue())),
                "evicted record must not reappear after reload");
    }

    @Test
    void delayedUpdateAfterDeleteDoesNotRecreateDetailFile() {
        TestStorage storage = new TestStorage(name, 10, baseDir);
        Long id = storage.save(item("before", 1L));

        storage.delete(id);
        storage.update(item("late update", id));

        assertFalse(detailFile(id).exists(), "an update for a deleted id must not recreate its detail file");
        assertTrue(storage.getDataList().isEmpty());
    }

    @Test
    void deleteWaitsForInProgressUpdateAndRemovesItsDetailFile() throws Exception {
        BlockingUpdateStorage storage = new BlockingUpdateStorage(name, 10, baseDir);
        Long id = storage.save(item("before", 1L));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch deleteStarted = new CountDownLatch(1);

        try {
            Future<?> update = executor.submit(() -> storage.update(item("updated", id)));
            assertTrue(storage.updateReadExisting.await(5, TimeUnit.SECONDS),
                    "update did not read the existing record");
            Future<?> delete = executor.submit(() -> {
                deleteStarted.countDown();
                storage.delete(id);
            });

            assertTrue(deleteStarted.await(5, TimeUnit.SECONDS), "delete task did not start");
            assertThrows(TimeoutException.class, () -> delete.get(200, TimeUnit.MILLISECONDS),
                    "delete must wait until the synchronized update finishes");
            storage.continueUpdate.countDown();
            update.get(5, TimeUnit.SECONDS);
            delete.get(5, TimeUnit.SECONDS);
            assertFalse(detailFile(id).exists(), "delete must remove the detail written by the earlier update");
            assertTrue(storage.getDataList().isEmpty());
        } finally {
            storage.continueUpdate.countDown();
            executor.shutdownNow();
        }
    }
}
