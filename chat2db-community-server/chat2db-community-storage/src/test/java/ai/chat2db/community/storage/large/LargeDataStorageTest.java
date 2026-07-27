package ai.chat2db.community.storage.large;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
