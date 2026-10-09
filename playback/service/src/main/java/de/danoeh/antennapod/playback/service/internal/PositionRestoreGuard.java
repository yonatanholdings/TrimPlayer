package de.danoeh.antennapod.playback.service.internal;

/**
 * Protects the saved listening position while a forward seek has not landed.
 *
 * <p>Armed from two places, both via {@link #armIfBehind}: the resume-time restore seek (the
 * original case, below) and {@code PlaybackService.seekTo} for <b>every</b> forward seek — auto-skip
 * and user scrub alike. The second was added on 2026-10-09 after two further ways to lose the
 * listener's place turned up on-device: the automatic saver firing in the gap between
 * {@code seekTo()} and the seek settling (a seek to 17862824ms logged
 * {@code position-regression ... to=26599ms} 18ms later), and a source that cannot serve the target
 * at all — a headerless MP3 with an unseekable SeekMap resolves every seek to 0, ExoPlayerWrapper's
 * collapse guard bounces once, the bounce collapses too, and the saver then persists the
 * played-from-the-top playhead (2026-10-08, ep 11836: three regressions in 35s, episode left at
 * 8761ms). The guard cannot make a seek land — only the extractor flags can — but it stops a failed
 * one from destroying where the listener was.
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
    /** Lowest playhead seen since arming. Progress for {@link #sustainedListening} is measured from
     *  here, not from the playhead at arming time: when the guard is armed by a SEEK, the arming
     *  playhead is where the listener was BEFORE the seek, so a source that collapsed the seek and
     *  restarted the episode at 0 would never show progress against it and the guard would veto
     *  every write until they had re-listened past their old position. The restart is the baseline
     *  that matters. */
    private int lowestSeenMs;
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
        lowestSeenMs = playerPosMs;
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
        observe(writePosMs);
        return isArmed() && writePosMs < targetMs - THRESHOLD_MS;
    }

    /** Track the restart baseline. Every method that is handed a live playhead funnels through
     *  here so {@link #sustainedListening} measures from the lowest point actually observed. */
    private void observe(int playerPosMs) {
        if (isArmed() && playerPosMs >= 0 && playerPosMs < lowestSeenMs) {
            lowestSeenMs = playerPosMs;
        }
    }

    /** True once the playhead is at (or past) the restore target. */
    public boolean landed(int playerPosMs) {
        observe(playerPosMs);
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
        observe(playerPosMs);
        return isArmed()
                && nowMs - armedAtMs >= SUSTAINED_LISTEN_MS
                && playerPosMs - lowestSeenMs >= SUSTAINED_PROGRESS_MS;
    }
}
