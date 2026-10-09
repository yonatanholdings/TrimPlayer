package de.danoeh.antennapod.playback.service.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the position-protection policy for a resume-time restore seek that never landed —
 * the "returned to the beginning" data loss on streamed episodes (2026-10-04, המנגל 198:
 * 43:00 overwritten with 4113ms, episode then marked played at 94s).
 */
public class PositionRestoreGuardTest {

    private static final long SETTLE = PositionRestoreGuard.SETTLE_MS;

    private static final long T0 = 1_000_000L;

    @Test
    public void mangalDriveSavedPositionIsNotOverwritten() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        // Resume at 43:00 on a stream that restarted at ~4s.
        assertTrue(g.armIfBehind(2_576_200, 4_113, T0));
        assertEquals(2_576_200, g.targetMs());
        // The saver tick that destroyed the position must now be dropped.
        assertTrue(g.vetoSave(4_113));
        assertTrue(g.vetoSave(11_183));
    }

    @Test
    public void doesNotArmWhenTheRestoreLanded() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        assertFalse(g.armIfBehind(2_576_200, 2_576_100, T0));
        assertFalse(g.isArmed());
        assertFalse("nothing armed, nothing vetoed", g.vetoSave(0));
    }

    @Test
    public void doesNotArmForARoutineRewindOrFreshEpisode() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        // 10s rewind-on-resume is legitimate and under the threshold.
        assertFalse(g.armIfBehind(600_000, 590_000, T0));
        // Episode with no remembered position.
        assertFalse(g.armIfBehind(0, 0, T0));
    }

    @Test
    public void forwardWritesAreAlwaysAllowed() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        g.armIfBehind(2_576_200, 4_113, T0);
        // A write at/after the target is the restored position — must be saved.
        assertFalse(g.vetoSave(2_576_200));
        assertFalse(g.vetoSave(2_600_000));
        // Just inside the tolerance band also counts as restored.
        assertFalse(g.vetoSave(2_576_200 - PositionRestoreGuard.THRESHOLD_MS + 1));
    }

    @Test
    public void retriesTheSeekOnceAfterSettling() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        g.armIfBehind(2_576_200, 4_113, T0);
        assertNull("too early to judge", g.retryTarget(4_113, T0 + 500));
        assertEquals(Integer.valueOf(2_576_200),
                g.retryTarget(4_113, T0 + PositionRestoreGuard.SETTLE_MS));
        assertNull("only one retry per arming", g.retryTarget(4_113, T0 + 5_000));
    }

    @Test
    public void noRetryOnceTheSeekLanded() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        g.armIfBehind(2_576_200, 4_113, T0);
        assertTrue(g.landed(2_570_000));
        assertNull(g.retryTarget(2_570_000, T0 + 5_000));
    }

    @Test
    public void releasesWhenTheListenerCarriesOnFromTheRestart() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        g.armIfBehind(2_576_200, 4_113, T0);
        // A minute in but barely moved (buffering/stalled): still protected.
        assertFalse(g.sustainedListening(10_000, T0 + 61_000));
        // A minute in and genuinely advanced: their progress is the truth now.
        assertTrue(g.sustainedListening(4_113 + 40_000, T0 + 61_000));
        // ...and before a full minute, no.
        assertFalse(g.sustainedListening(4_113 + 40_000, T0 + 30_000));
    }

    @Test
    public void disarmStopsVetoing() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        g.armIfBehind(2_576_200, 4_113, T0);
        g.disarm();
        assertFalse(g.isArmed());
        assertFalse(g.vetoSave(4_113));
        assertNull(g.retryTarget(4_113, T0 + 10_000));
    }

    @Test
    public void rearmingResetsTheRetry() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        g.armIfBehind(2_576_200, 4_113, T0);
        g.retryTarget(4_113, T0 + PositionRestoreGuard.SETTLE_MS);
        g.disarm();
        g.armIfBehind(1_000_000, 2_000, T0 + 100_000);
        assertEquals(Integer.valueOf(1_000_000),
                g.retryTarget(2_000, T0 + 100_000 + PositionRestoreGuard.SETTLE_MS));
    }

    // --- Seek-time arming (2026-10-09): the guard now also protects the destination of every
    // forward seek, not just the resume-time restore. PlaybackService.seekTo arms it with
    // (target, pre-seek playhead), so these cases describe what it must do there.

    @Test
    public void protectsTheDestinationOfAForwardSeekWhileItSettles() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        // Scrub from 2:06 to 4:57:42 on a 6h episode (the 2026-10-09 on-device case).
        assertTrue(g.armIfBehind(17_862_824, 126_052, T0));
        // The automatic saver fires in the gap before the seek settles and offers the pre-seek
        // playhead. That write is what produced "position-regression ... to=26599ms".
        assertTrue(g.vetoSave(26_599));
        assertTrue(g.vetoSave(126_052));
        // Once the seek lands, the guard gets out of the way immediately.
        assertTrue(g.landed(17_862_824));
    }

    @Test
    public void aDeliberateRewindIsNeverProtected() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        // Seeking BACKWARDS: target is behind the playhead, so there is nothing to protect and the
        // listener's new, smaller position must be saved normally.
        assertFalse(g.armIfBehind(60_000, 600_000, T0));
        assertFalse(g.isArmed());
        assertFalse(g.vetoSave(60_000));
    }

    @Test
    public void aShortForwardSeekIsNotProtected() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        // A 30s fast-forward is inside THRESHOLD_MS of nothing-to-protect territory: it lands
        // instantly and must not start vetoing writes.
        assertFalse(g.armIfBehind(130_000, 120_000, T0));
        assertFalse(g.isArmed());
    }

    @Test
    public void anUnservableSeekStopsDestroyingThePositionButStillYieldsEventually() {
        PositionRestoreGuard g = new PositionRestoreGuard();
        // ep 11836, 2026-10-08: auto-skip 166698 -> 242742 on a source that resolves every seek
        // to 0, so the playhead restarts at the top and climbs.
        assertTrue(g.armIfBehind(242_742, 166_698, T0));
        assertTrue(g.vetoSave(12_091));     // the write that destroyed the episode
        assertTrue(g.vetoSave(5_597));
        assertTrue(g.vetoSave(8_746));
        // One retry of the seek is offered after the settle delay...
        assertEquals(Integer.valueOf(242_742), g.retryTarget(12_091, T0 + SETTLE));
        assertNull(g.retryTarget(12_091, T0 + SETTLE + 5_000));
        // ...and if the listener simply carries on from the restart, their progress wins so the
        // episode does not freeze at a position they have left behind. Progress is measured from
        // the RESTART (the lowest playhead seen, 5597 above), not from the pre-seek position --
        // otherwise this escape hatch could never fire for a seek-armed guard.
        assertFalse(g.sustainedListening(20_000, T0 + 61_000));          // only 14s on from 5597
        assertTrue(g.sustainedListening(5_597 + 31_000, T0 + 61_000));   // 31s on: they moved on
    }
}
