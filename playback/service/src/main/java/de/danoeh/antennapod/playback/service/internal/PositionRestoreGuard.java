package de.danoeh.antennapod.playback.service.internal;

/**
 * Protects the saved listening position while a resume-time restore seek has not landed.
 *
 * <p><b>Why this exists:</b> resuming a STREAMED episode seeks to the remembered position while the
 * player is PREPARED, but the stream can restart near 0 instead (the host ignores the Range
 * request, so ExoPlayer re-prepares the source from the top). {@code LocalPSMP.getPosition()} then
 * reports that small playhead — its fallback to the remembered position only fires at {@code <= 0} —
 * and the automatic position saver persists it, overwriting the real position. The listener's place
 * is destroyed, not merely mis-seeked: on 2026-10-04 "המנגל 198" was 43:00 in, got rewritten to
 * 4113ms, replayed from the top (intro auto-skipped again) and ended up marked played at 94s. The
 * same shape hit three streamed episodes on three different hosts (podbean, blubrry, anchor) in
 * eight days, each logged as {@code position-regression ... by=PlaybackService.saveCurrentPosition}.
 *
 * <p>While armed, an automatic write that would move the stored position backwards is dropped and
 * the restore seek is retried once. The guard releases when the seek lands, when the listener seeks
 * by hand (explicit intent), when they have clearly gone on listening from the new spot, or when the
 * episode changes — so a genuinely restarted stream still records progress.
 *
 * <p>Pure state machine with an injected clock so the whole policy is unit-testable on the JVM.
 */
public final class PositionRestoreGuard {

    /** A write this far behind the restore target is treated as the un-restored playhead.
     *  Matches {@code PlayableUtils.REGRESSION_THRESHOLD_MS}, i.e. above any legitimate rewind. */
    public static final int THRESHOLD_MS = 15_000;

    /** Wait this long after playback starts before judging the seek — it completes well inside. */
    public static final long SETTLE_MS = 1_500;

    /** Treat the listener as having moved on once they have played this long from the new spot. */
    public static final long SUSTAINED_LISTEN_MS = 60_000;

    /** ...and advanced at least this far, so a stalled/buffering player never counts. */
    public static final int SUSTAINED_PROGRESS_MS = 30_000;

    private int targetMs = -1;
    private int armedPlayerPosMs;
    private long armedAtMs;
    private boolean retried;

    /**
     * Arm when playback starts far behind the position the episode itself remembers.
     *
     * @return true when the guard armed (i.e. the restore has not landed)
     */
    public boolean armIfBehind(int rememberedMs, int playerPosMs, long nowMs) {
        if (rememberedMs <= 0 || playerPosMs < 0 || rememberedMs - playerPosMs <= THRESHOLD_MS) {
            return false;
        }
        targetMs = rememberedMs;
        armedPlayerPosMs = playerPosMs;
        armedAtMs = nowMs;
        retried = false;
        return true;
    }

    public boolean isArmed() {
        return targetMs >= 0;
    }

    public int targetMs() {
        return targetMs;
    }

    public void disarm() {
        targetMs = -1;
        retried = false;
    }

    /** True when this automatic write must be dropped: it would overwrite the remembered position
     *  with the playhead of a restore that never landed. */
    public boolean vetoSave(int writePosMs) {
        return isArmed() && writePosMs < targetMs - THRESHOLD_MS;
    }

    /** True once the playhead is at (or past) the restore target. */
    public boolean landed(int playerPosMs) {
        return isArmed() && playerPosMs >= targetMs - THRESHOLD_MS;
    }

    /** The target to re-seek to, once per arming, after the settle delay. Null when not due. */
    public Integer retryTarget(int playerPosMs, long nowMs) {
        if (!isArmed() || retried || nowMs < armedAtMs + SETTLE_MS || landed(playerPosMs)) {
            return null;
        }
        retried = true;
        return targetMs;
    }

    /** True when the listener has plainly carried on from where the stream restarted, so their
     *  progress — not the old position — is now the truth worth saving. */
    public boolean sustainedListening(int playerPosMs, long nowMs) {
        return isArmed()
                && nowMs - armedAtMs >= SUSTAINED_LISTEN_MS
                && playerPosMs - armedPlayerPosMs >= SUSTAINED_PROGRESS_MS;
    }
}
