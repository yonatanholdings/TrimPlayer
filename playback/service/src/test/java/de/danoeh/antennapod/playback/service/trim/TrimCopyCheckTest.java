package de.danoeh.antennapod.playback.service.trim;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the copy-mismatch guard: segment times measured on a differently ad-stitched copy of the
 * episode must not be auto-skipped on ours, and a matching copy must never be flagged.
 */
public class TrimCopyCheckTest {

    @Test
    public void howIBuiltThisDriveIsFlaggedByTheFloor() {
        // 2026-09-10: no stored exact duration; the server's latest fingerprint sat at 5523.23s,
        // the phone's file is 4887.72s long. Three skips in 12s all cut the interview.
        assertTrue(TrimCopyCheck.copyMismatch(4_887_719, null, 5523.23));
    }

    @Test
    public void megaphoneExactDurationOffBy37sIsFlagged() {
        assertTrue(TrimCopyCheck.copyMismatch(3_000_000, 3037.3, null));
        assertTrue(TrimCopyCheck.copyMismatch(3_000_000, 2962.7, null));
    }

    @Test
    public void sameFileWithinToleranceIsNotFlagged() {
        // Same file on both ends measures within ~1s; pdst.fm showed 6.7s on the test phone.
        assertFalse(TrimCopyCheck.copyMismatch(3_000_000, 3000.6, null));
        assertFalse(TrimCopyCheck.copyMismatch(3_000_000, 2993.3, 2990.0));
    }

    @Test
    public void lowFloorNeverFlagsAMatchingFile() {
        // A floor far below our length (an intro-only fingerprint) proves nothing.
        assertFalse(TrimCopyCheck.copyMismatch(3_000_000, null, 174.0));
        // Our file is LONGER than the floor — consistent with any server length above it.
        assertFalse(TrimCopyCheck.copyMismatch(3_000_000, null, 2995.0));
    }

    @Test
    public void floorStillCatchesAWrongExactDuration() {
        // Exact says "match" but the server provably fingerprinted past our end.
        assertTrue(TrimCopyCheck.copyMismatch(3_000_000, 3000.0, 3200.0));
    }

    @Test
    public void unknownLengthsNeverFlag() {
        assertFalse(TrimCopyCheck.copyMismatch(0, 3037.3, 5523.0));
        assertFalse(TrimCopyCheck.copyMismatch(-1, 3037.3, null));
        assertFalse(TrimCopyCheck.copyMismatch(3_000_000, null, null));
        assertFalse(TrimCopyCheck.copyMismatch(3_000_000, 0.0, 0.0));
    }
}
