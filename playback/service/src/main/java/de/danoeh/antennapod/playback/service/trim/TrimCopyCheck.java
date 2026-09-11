package de.danoeh.antennapod.playback.service.trim;

/**
 * Decides whether the backend's segment timestamps fit the file this device is playing.
 *
 * <p><b>Why this exists:</b> segment times are measured on the server's copy of the episode.
 * Dynamic-ad-insertion hosts (art19, megaphone, ...) stitch a different ad load per listener
 * region and per week, so one enclosure URL yields files of different lengths with the show
 * shifted inside them. Applied to a differently-assembled file, every skip lands on the wrong
 * audio. How I Built This, 2026-09-10: the server's copy was at least 5523s, the phone's 4888s
 * with no pre-roll at all, and three auto-skips in 12 seconds all cut the interview. When the
 * two copies provably differ, the player stands down rather than cut content.
 */
public final class TrimCopyCheck {

    /**
     * How far the two lengths may drift before we call them different files. The same file
     * measures within ~1s on both ends (anchor, soundcloud, podbean and buzzsprout episodes on
     * the test phone were all within 0.6s of the backend on 2026-09-11); one inserted ad is 15s+.
     */
    public static final int TOLERANCE_MS = 10_000;

    private TrimCopyCheck() {
    }

    /**
     * True when the backend's copy provably differs from ours.
     *
     * @param clientDurationMs     our player's duration; {@code <= 0} while still unknown
     * @param serverDurationSec    exact length of the server's copy, or null
     * @param serverMinDurationSec proven lower bound on the server copy's length, or null
     */
    public static boolean copyMismatch(int clientDurationMs, Double serverDurationSec,
                                       Double serverMinDurationSec) {
        if (clientDurationMs <= 0) {
            return false;
        }
        if (serverDurationSec != null && serverDurationSec > 0
                && Math.abs(clientDurationMs - Math.round(serverDurationSec * 1000)) > TOLERANCE_MS) {
            return true;
        }
        // A floor only proves "at least this long", so it can only catch a file of ours that is
        // shorter than the server's. It can never flag a matching file, however low it sits.
        return serverMinDurationSec != null && serverMinDurationSec > 0
                && Math.round(serverMinDurationSec * 1000) > (long) clientDurationMs + TOLERANCE_MS;
    }
}
