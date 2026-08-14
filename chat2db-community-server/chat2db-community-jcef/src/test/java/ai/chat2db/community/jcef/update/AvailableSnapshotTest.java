package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailableSnapshotTest {

    @Test
    void retainsExactBytes() {
        byte[] bytes = "manifest".getBytes(StandardCharsets.UTF_8);
        AvailableSnapshot snapshot = new AvailableSnapshot("5.3.2", bytes, 1_000_000);

        assertArrayEquals(bytes, snapshot.exactBytes());
        assertEquals("5.3.2", snapshot.version());
        assertEquals(1_000_000, snapshot.fetchedAtNanos());
    }

    @Test
    void exactBytesAreDefensiveCopy() {
        byte[] bytes = "manifest".getBytes(StandardCharsets.UTF_8);
        AvailableSnapshot snapshot = new AvailableSnapshot("5.3.2", bytes, 1_000_000);

        snapshot.exactBytes()[0] = 'X';

        assertArrayEquals("manifest".getBytes(StandardCharsets.UTF_8), snapshot.exactBytes());
    }

    @Test
    void detectsExpirationWithMonotonicClock() {
        AtomicLong clock = new AtomicLong(0);
        AvailableSnapshot snapshot = new AvailableSnapshot("5.3.2", new byte[0], clock.get());

        clock.addAndGet(AvailableSnapshot.TTL_NANOS - 1);
        assertFalse(snapshot.isExpired(clock.get()));

        clock.addAndGet(2);
        assertTrue(snapshot.isExpired(clock.get()));
    }

    @Test
    void ttlIsThirtyMinutes() {
        assertEquals(TimeUnit.MINUTES.toNanos(30), AvailableSnapshot.TTL_NANOS);
    }

    @Test
    void sameBytesComparesExactContent() {
        byte[] bytes = "manifest".getBytes(StandardCharsets.UTF_8);
        AvailableSnapshot snapshot = new AvailableSnapshot("5.3.2", bytes, 0);

        assertTrue(snapshot.sameBytes("manifest".getBytes(StandardCharsets.UTF_8)));
        assertFalse(snapshot.sameBytes("different".getBytes(StandardCharsets.UTF_8)));
    }
}
