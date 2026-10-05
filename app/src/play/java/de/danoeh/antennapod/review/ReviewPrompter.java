package de.danoeh.antennapod.review;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

import de.danoeh.antennapod.playback.service.PlaybackService;

/**
 * Play-flavour in-app review prompt.
 *
 * WHY: the store listing has had zero ratings since launch, and a listing with no
 * ratings converts worse for every visitor it gets — which matters more than usual
 * while the top of the funnel is thin. Nothing in the app had ever asked.
 *
 * WHEN: only after the user has felt the thing we would be asking them to rate.
 * {@link PlaybackService#KEY_TRIM_SKIP_COUNT} counts auto-skips actually performed,
 * so an install whose shows are all cold — or where auto-skip is failing — never
 * sees a prompt. That is deliberate: asking there would harvest one-star reviews.
 *
 * Play itself decides whether to actually show anything (quota, recent review,
 * Play Store state) and deliberately reports neither outcome nor whether a review
 * was left. So this asks at most once per install: we cannot tell a shown-and-
 * dismissed prompt from a throttled one, and re-asking would risk nagging.
 *
 * The twin in app/src/free/ is a no-op and must keep the same public API — the
 * caller (MainActivity) is flavour-agnostic.
 */
public final class ReviewPrompter {
    private static final String TAG = "ReviewPrompter";

    /** Skips before we consider asking. Two is a coincidence; five is a pattern. */
    private static final int MIN_SKIPS = 5;
    private static final String KEY_REVIEW_ASKED = "reviewAsked";

    private ReviewPrompter() {
    }

    /** Ask for a review if this install has earned the question. Safe to call on
     *  every resume; it returns immediately in the common case. */
    public static void maybePrompt(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences(
                PlaybackService.PREF_TRIM_ANALYTICS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_REVIEW_ASKED, false)) {
            return;
        }
        if (prefs.getInt(PlaybackService.KEY_TRIM_SKIP_COUNT, 0) < MIN_SKIPS) {
            return;
        }
        try {
            ReviewManager manager = ReviewManagerFactory.create(activity);
            manager.requestReviewFlow().addOnCompleteListener(request -> {
                if (!request.isSuccessful()) {
                    // Transient (no Play Store, no network). Leave the flag unset so
                    // a later resume can try again rather than burning the one ask.
                    Log.d(TAG, "review flow unavailable: " + request.getException());
                    return;
                }
                ReviewInfo info = request.getResult();
                manager.launchReviewFlow(activity, info).addOnCompleteListener(flow -> {
                    // Mark asked whatever happens: Play returns success even when it
                    // showed nothing, so this is the only point at which we can stop.
                    prefs.edit().putBoolean(KEY_REVIEW_ASKED, true).apply();
                    Log.d(TAG, "review flow finished");
                });
            });
        } catch (Exception e) {
            // Never let a store-side problem take down onResume.
            Log.w(TAG, "review prompt failed: " + e);
        }
    }
}
