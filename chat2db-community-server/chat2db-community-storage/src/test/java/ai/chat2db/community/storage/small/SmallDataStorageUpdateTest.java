package ai.chat2db.community.storage.small;

import ai.chat2db.community.storage.TestHome;
import lombok.Data;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for core:storage-5: update() must not insert a record
 * when the id does not exist (mirrors LargeDataStorage.update).
 */
class SmallDataStorageUpdateTest {

    @BeforeAll
    static void useTempHome() {
        TestHome.init();
    }

    @Test
    void updateWithMissingIdDoesNotInsertRecord() {
        SmallDataStorage<TestBean> storage = newStorage();
        TestBean update = new TestBean();
        update.setId(424242L);
        update.setName("ghost");

        storage.update(update);

        assertNull(storage.getById(424242L));
        assertTrue(storage.getDataList().isEmpty());
    }

    @Test
    void updateWithExistingIdStillAppliesChanges() {
        SmallDataStorage<TestBean> storage = newStorage();
        TestBean bean = new TestBean();
        bean.setName("before");
        Long id = storage.save(bean);

        TestBean update = new TestBean();
        update.setId(id);
        update.setName("after");
        storage.update(update);

        assertEquals("after", storage.getById(id).getName());
        assertEquals(1, storage.getDataList().size());
    }

    private static SmallDataStorage<TestBean> newStorage() {
        return SmallDataStorage.create("test-small-update-" + UUID.randomUUID(), TestBean.class);
    }

    @Data
    public static class TestBean {
        private Long id;
        private String name;
    }
}
