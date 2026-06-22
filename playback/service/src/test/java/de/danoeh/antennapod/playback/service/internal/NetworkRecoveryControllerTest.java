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

    /** Player whose prepare() drops the position to 0 — the documented error-during-seek reset.
     *  Set {@link #dropOnPrepare} false to model a plain mid-playback stall where prepare() keeps it. */
    private static class FakePlayer implements NetworkRecoveryController.Player {
        long position;
        int prepareCount;
        int seekCount;
        boolean dropOnPrepare = true;

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
            if (dropOnPrepare) {
                position = 0; // re-prepare after an error-during-unbuffered-seek resolves to default pos
            }
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
        host.fire();                      // backoff elapses, recovery re-prepares (drops position)
        controller.onPlayerReady();       // back online + loadable → restore the captured position

        // Before the fix this is 0 — the episode restarted from the beginning.
        assertEquals("episode must resume where it was, not from 0",
                120_000, player.getCurrentPositionMs());
    }

    @Test
    public void doesNotSeekWhileOfflineThenRestoresOnReady() {
        // The restart-from-0 root cause: recover() used to seek immediately, but seeking a progressive
        // stream into an un-buffered/offline region collapses to ~0. recover() must NOT seek; the
        // restore happens only once the player reaches ready (back online), where the seek lands.
        FakePlayer player = new FakePlayer(120_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError();
        host.fire(); // re-prepare while still offline
        assertEquals("must NOT seek while offline — that is what collapses to 0", 0, player.seekCount);

        controller.onPlayerReady(); // back online + loadable
        assertEquals(1, player.seekCount);
        assertEquals(120_000, player.getCurrentPositionMs());
    }

    @Test
    public void retainedPositionIsNotReSoughtOnReady() {
        // A plain mid-playback stall: prepare() keeps the position. The restore must be a no-op
        // (no needless flush/seek) — only a genuinely-lost position is re-sought.
        FakePlayer player = new FakePlayer(200_000);
        player.dropOnPrepare = false;
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError();
        host.fire();
        controller.onPlayerReady();

        assertEquals("retained position must not be re-sought", 0, player.seekCount);
        assertEquals(200_000, player.getCurrentPositionMs());
    }

    @Test
    public void restoreSeekSuccessCompletesRecoveryAndResetsBudget() {
        FakePlayer player = new FakePlayer(300_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError();
        host.fire();
        controller.onPlayerReady();          // issues the restore seek; stays pending until confirmed
        controller.onRestoreSeekSucceeded(); // host confirms it landed

        host.delays.clear();
        controller.onRecoverableError(); // a fresh outage must back off from the base again
        assertEquals("budget must reset once the restore is confirmed", (Long) 1000L, host.delays.get(0));
        assertEquals(300_000, player.getCurrentPositionMs());
    }

    @Test
    public void restoreSeekCollapseKeepsRecovering() {
        FakePlayer player = new FakePlayer(300_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError();
        host.fire();
        controller.onPlayerReady(); // restore seek issued
        int delaysBefore = host.delays.size();

        controller.onRestoreSeekCollapsed(); // restore landed at ~0; must NOT accept it
        assertEquals("a collapsed restore must schedule another re-prepare",
                delaysBefore + 1, host.delays.size());
        assertEquals(0, host.exhaustedCount);
    }

    @Test
    public void restoreSeekCollapseThenSucceedsRestoresPosition() {
        FakePlayer player = new FakePlayer(300_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError();
        host.fire();
        controller.onPlayerReady();          // seek -> 300_000
        player.position = 0;                 // model the restore collapsing back to the start
        controller.onRestoreSeekCollapsed(); // reschedules
        host.fire();                         // re-prepare -> 0
        controller.onPlayerReady();          // seek -> 300_000 again
        controller.onRestoreSeekSucceeded(); // this time it sticks

        assertEquals(300_000, player.getCurrentPositionMs());
    }

    @Test
    public void restoreSeekCollapseSurfacesErrorOnceBudgetSpent() {
        FakePlayer player = new FakePlayer(300_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError(); // attempts = 1
        host.fire();
        controller.onPlayerReady();      // restore pending
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            controller.onRestoreSeekCollapsed();
        }
        assertEquals(1, host.exhaustedCount);
    }

    @Test
    public void restoreSeekCollapseIsNoOpWhenNothingPending() {
        FakePlayer player = new FakePlayer(5_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRestoreSeekCollapsed(); // no restore in flight

        assertTrue(host.delays.isEmpty());
        assertEquals(0, host.exhaustedCount);
    }

    @Test
    public void positionCapturedOnceAndSurvivesRepeatedFailedReprepares() {
        // The outage persists across several attempts: each prepare() zeroes the position, but the
        // captured resume position must stay the original good one (not re-read as 0).
        FakePlayer player = new FakePlayer(300_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError();
        host.fire(); // attempt 1: prepare -> 0 (restore deferred to ready)
        controller.onRecoverableError(); // still down; must NOT recapture the now-zero position
        host.fire(); // attempt 2: prepare -> 0
        controller.onPlayerReady(); // back online -> restore the once-captured position

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

    @Test
    public void connectivityRestoredRetriesImmediatelyAndKeepsPosition() {
        FakePlayer player = new FakePlayer(90_000); // 1:30 in
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onRecoverableError();        // drop: schedules a backoff retry we'd wait out
        controller.onConnectivityRestored();    // network is back before the timer elapses

        // An immediate (0-delay) retry is scheduled in place of waiting out the backoff.
        assertEquals("connectivity-restore must schedule an immediate retry",
                (Long) 0L, host.delays.get(host.delays.size() - 1));
        host.fire();                // immediate re-prepare (online again)
        controller.onPlayerReady(); // source loadable → restore position
        assertEquals("resume position preserved across the connectivity-triggered re-prepare",
                90_000, player.getCurrentPositionMs());
        assertTrue(player.prepareCount >= 1);
    }

    @Test
    public void connectivityRestoredIsNoOpWhenNoRecoveryInFlight() {
        FakePlayer player = new FakePlayer(50_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        controller.onConnectivityRestored(); // nothing was wrong — must not touch the player

        assertTrue("must not schedule anything when idle", host.delays.isEmpty());
        assertEquals(0, host.bufferingCount);
        assertEquals(0, player.prepareCount);
    }

    @Test
    public void connectivityRestoredResetsBackoffRamp() {
        FakePlayer player = new FakePlayer(5_000);
        FakeHost host = new FakeHost();
        NetworkRecoveryController controller = controller(player, host);

        for (int i = 0; i < 5; i++) { // ramp the backoff up to 16s
            controller.onRecoverableError();
            host.fire();
        }
        assertEquals((Long) 16_000L, host.delays.get(host.delays.size() - 1));

        controller.onConnectivityRestored(); // network back: immediate retry + ramp reset
        host.fire();                          // immediate retry runs, still failing underneath
        host.delays.clear();

        controller.onRecoverableError(); // a subsequent failure backs off from near the base again
        assertEquals("backoff ramp must reset after a connectivity-triggered retry",
                (Long) 2000L, host.delays.get(0));
    }
}
