package de.danoeh.antennapod.review;

import android.app.Activity;

/**
 * Free-flavour review prompt: deliberately does nothing.
 *
 * The free flavour ships with no proprietary Google libraries (F-Droid,
 * IzzyOnDroid and the FOSS lists require it), and the in-app review API is part
 * of the Play Core library. There is also nothing to link to — these builds are
 * not installed from Play.
 *
 * Keep the public API identical to the `play` twin in
 * app/src/play/java/de/danoeh/antennapod/review/ReviewPrompter.java.
 */
public final class ReviewPrompter {

    private ReviewPrompter() {
    }

    /** No-op. */
    public static void maybePrompt(Activity activity) {
    }
}
