package ai.chat2db.community.domain.core.impl.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AIMD tuning of the concurrency gate: permits grow on throughput improvement, shrink fast on
 * regression, stay hard-capped at the configured max even while workers hold permits, and react
 * to source pressure by cutting a quarter of the fan-out. Tuning windows are row-based: one
 * observation with at least {@link AdaptiveConcurrencyGate#WINDOW_ROWS} rows triggers one
 * evaluation, so successive windows model faster execution with smaller wall times.
 */
class AdaptiveConcurrencyGateTest {

    private static final long MILLI = 1_000_000L;

    /** Feeds one full tuning window executed in {@code millis} (rows are the window size). */
    private void tune(AdaptiveConcurrencyGate gate, long millis) {
        gate.record(AdaptiveConcurrencyGate.WINDOW_ROWS, millis * MILLI);
    }

    @Test
    void growsOnImprovementUpToTheCap() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        assertEquals(2, gate.currentPermits());
        tune(gate, 10); // first window only establishes the baseline
        assertEquals(2, gate.currentPermits());
        tune(gate, 5);
        assertEquals(3, gate.currentPermits());
        tune(gate, 2);
        assertEquals(4, gate.currentPermits());
        tune(gate, 1);
        assertEquals(4, gate.currentPermits(), "permits must never exceed the configured max");
    }

    @Test
    void growsFromBelowTheFloor() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(1, 4);
        assertEquals(1, gate.currentPermits());
        tune(gate, 10); // baseline
        tune(gate, 5);
        assertEquals(2, gate.currentPermits());
        tune(gate, 2);
        assertEquals(3, gate.currentPermits());
        tune(gate, 1);
        assertEquals(4, gate.currentPermits());
    }

    @Test
    void hardCapHoldsEvenWhileWorkersHoldPermits() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        gate.tryAcquire();
        gate.tryAcquire(); // both initial permits are held by workers now
        assertEquals(0, gate.currentPermits());
        tune(gate, 10); // baseline
        tune(gate, 5);
        tune(gate, 2);
        tune(gate, 1);
        gate.relinquish(true);
        gate.relinquish(true);
        // The old available-permits-based guard let the total drift one past the max; the hard
        // total cap must keep it at exactly the configured ceiling.
        assertEquals(4, gate.currentPermits());
    }

    @Test
    void shrinksFastOnRegressionAndStaysAtTheFloor() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(4, 4);
        tune(gate, 2); // baseline (fast windows: high throughput)
        tune(gate, 10); // regression: a quarter of 4 permits cut
        assertEquals(3, gate.currentPermits());
        tune(gate, 20); // regression: at least one permit cut
        assertEquals(2, gate.currentPermits());
        tune(gate, 40);
        tune(gate, 80);
        tune(gate, 100);
        assertEquals(2, gate.currentPermits(), "the fan-out must never drop below the floor");
    }

    @Test
    void keepsAStableThroughputFlat() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        tune(gate, 10); // baseline
        tune(gate, 10); // identical throughput: neither grow nor cut
        assertEquals(2, gate.currentPermits());
    }

    @Test
    void ignoresInvalidObservations() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        gate.record(0, MILLI);
        gate.record(100, 0L);
        assertEquals(2, gate.currentPermits());
    }

    @Test
    void sourcePressureCutsAQuarterImmediatelyButNeverPastTheFloor() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(4, 4);
        gate.reduceForSourcePressure();
        assertEquals(3, gate.currentPermits(), "a slow page cuts a quarter of the fan-out");
        AdaptiveConcurrencyGate floored = AdaptiveConcurrencyGate.create(2, 4);
        floored.reduceForSourcePressure();
        assertEquals(2, floored.currentPermits(), "the floor holds under source pressure");
    }

    @Test
    void boundedAdmitDegradesInsteadOfHanging() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(1, 4);
        gate.tryAcquire(); // the only permit is held
        assertFalse(gate.admit(1L), "a stuck gate must report failure instead of blocking forever");
        gate.relinquish(false); // no permit taken: a no-op
        gate.relinquish(true);
        assertTrue(gate.admit(1L), "the returned permit is admitted again");
        gate.relinquish(true);
        assertEquals(1, gate.currentPermits());
    }
}
