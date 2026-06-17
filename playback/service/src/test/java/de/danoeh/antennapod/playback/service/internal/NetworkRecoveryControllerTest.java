package de.danoeh.antennapod.playback.service.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link NetworkRecoveryController}.
 *
 * <p>The headline test ({@link #recoveryRestoresPositionWhenReprepareResetsToZero}) reproduces the
 * field bug: a trim ad-skip seeks forward into un-buffered stream, the seek hits a network dead-zone
 * mid-run, recovery re-prepares — and the episode jumps back to the beginning because {@code prepare()}
 * lost the position. {@link FakePlayer#prepare()} models that reset; the controller must seek back.
 */
public class NetworkRecoveryControllerTest {

    private static final int MAX_ATTEMPTS = 12;
    private static final long BASE_DELAY = 1000;
    private static final long MAX_DELAY = 30_000;

    /** Player whose prepare() drops the position to 0 — the documented error-during-seek reset. */
    private static class FakePlayer implements NetworkRecoveryController.Player {
        long position;
        int prepareCount;
        int seekCount;

        FakePlayer(long startPosition) {
            this.position = startPosition;
        }

        @Override
        public long getCurrentPositionMs() {
            return position;
        }

        @Override
        public void prepare() {
            prepareCount++;
            position = 0; // re-prepare after an error-during-unbuffered-seek resolves to default pos
        }

        @Override
        public void seekTo(long positionMs) {
            seekCount++;
            position = positionMs;
        }
    }

    private static class FakeHost implements NetworkRecoveryController.Host {
        Runnable pending;
        final List<Long> delays = new ArrayList<>();
        int bufferingCount;
        int exhaustedCount;

        @Override
        public void scheduleRetry(long delayMs, Runnable retry) {
            delays.add(delayMs);
            pending = retry;
        }

        @Override
        public void cancelScheduled() {
            pending = null;
        }

        @Override
        public void onBuffering() {
            bufferingCount++;
        }

        @Override
        public void onExhausted() {
            exhaustedCount++;
        }

        /** Fire the scheduled retry, as the real Handler would after the delay. */
        void fire() {
            Runnable r = pending;
            pending = null;
            if (r != null) {
                r.run();
            }
        }
    }

    private NetworkRecoveryController controller(FakePlayer player, FakeHost host) {
        return new NetworkRecoveryController(player, host, MAX_ATTEMPTS, BASE_DELAY, MAX_DELAY);
    }

    @Test
    public void recoveryRestoresPositionWhenReprepareResetsToZero() {
        FakePlayer player = new FakePlayer(120_000); // mid-episode, 2:00 in
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError(); // network drops during the ad-skip seek
        host.fire();                      // backoff elapses, recovery re-prepares

        // Before the fix this is 0 — the episode restarted from the beginning.
        assertEquals("episode must resume where it was, not from 0",
                120_000, player.getCurrentPositionMs());
    }

    @Test
    public void positionCapturedOnceAndSurvivesRepeatedFailedReprepares() {
        // The outage persists across several attempts: each prepare() zeroes the position, but the
        // captured resume position must stay the original good one (not re-read as 0).
        FakePlayer player = new FakePlayer(300_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError();
        host.fire(); // attempt 1: prepare -> 0, restore -> 300_000 (if fixed)
        controller.onRecoverableError(); // still down; must NOT recapture the now-zero position
        host.fire(); // attempt 2

        assertEquals(300_000, player.getCurrentPositionMs());
    }

    @Test
    public void backoffIsExponentialAndCapped() {
        FakePlayer player = new FakePlayer(5_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        for (int i = 0; i < 8; i++) {
            controller.onRecoverableError();
            host.fire();
        }

        assertEquals((Long) 1000L, host.delays.get(0));
        assertEquals((Long) 2000L, host.delays.get(1));
        assertEquals((Long) 4000L, host.delays.get(2));
        assertEquals((Long) 8000L, host.delays.get(3));
        assertEquals((Long) 16_000L, host.delays.get(4));
        assertEquals((Long) 30_000L, host.delays.get(5)); // capped (would be 32_000)
        assertEquals((Long) 30_000L, host.delays.get(6));
    }

    @Test
    public void surfacesErrorAfterBudgetExhausted() {
        FakePlayer player = new FakePlayer(5_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            controller.onRecoverableError();
            host.fire();
        }
        assertEquals(0, host.exhaustedCount);

        controller.onRecoverableError(); // one past the budget
        assertEquals(1, host.exhaustedCount);
    }

    @Test
    public void readyResetsBudgetForNextOutage() {
        FakePlayer player = new FakePlayer(5_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            controller.onRecoverableError();
            host.fire();
        }
        controller.onPlayerReady(); // recovery confirmed

        host.delays.clear();
        controller.onRecoverableError(); // a later, separate outage
        assertEquals("budget must reset after READY", (Long) 1000L, host.delays.get(0));
        assertTrue(host.exhaustedCount == 0);
    }
}
