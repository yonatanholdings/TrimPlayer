package de.danoeh.antennapod.ui.screen.preferences.pro;

import android.content.Context;
import android.content.DialogInterface;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.playback.service.trim.EntitlementStore;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;

import android.content.Intent;

/**
 * One-shot dialogs related to the Pro paywall (Phase 1, 2026-05-19):
 *   - {@link #showQuotaUpsell}: soft paywall when free quota is exhausted.
 *     User can dismiss; we show at most once per resumed session so we don't
 *     spam someone who taps "Maybe later".
 *   - {@link #showBetaGrandfatherWelcomeIfNeeded}: celebratory dialog the
 *     first time the backend confirms this device was grandfathered.
 *
 * Kept stateless / static — owns no fragment lifecycle.
 */
public final class TrimProDialogs {
    private TrimProDialogs() { }

    /** Compile-time kill-switch — last resort if the server flag goes sideways.
     *  When true, Pro UI is hidden regardless of the server response. Leave
     *  false for normal operation; the server-driven {@code pro_ui_visible}
     *  field on /segments responses is the real control. */
    public static final boolean HIDDEN = false;

    /** True iff Pro UI surfaces should be visible right now. Combines the
     *  compile-time kill-switch (HIDDEN) with the runtime server flag from
     *  the last /segments response. Defaults to hidden if the server hasn't
     *  spoken yet — the user only sees Pro UI once the backend opts them in. */
    public static boolean isProUiVisible() {
        if (HIDDEN) {
            return false;
        }
        return de.danoeh.antennapod.playback.service.trim.EntitlementStore.get()
                .snapshot().proUiVisible;
    }

    // Session-scoped flag so we don't re-prompt within the same Activity resume.
    // Cleared when the process dies (acceptable: at most one prompt per cold start).
    private static volatile boolean upsellShownThisSession = false;

    /** Reset between distinct user actions if you want the upsell to be eligible
     *  to fire again (e.g. after user explicitly visited the Pro screen). */
    public static void resetUpsellSessionFlag() {
        upsellShownThisSession = false;
    }

    public static void showQuotaUpsell(FragmentActivity activity,
                                       EntitlementStore.Snapshot snapshot) {
        if (!isProUiVisible()) {
            return;
        }
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (upsellShownThisSession) {
            return;
        }
        upsellShownThisSession = true;

        int quota = snapshot != null && snapshot.quotaLimit != null
                ? snapshot.quotaLimit : 3;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.trim_upsell_title)
                .setMessage(activity.getString(R.string.trim_upsell_body, quota))
                .setPositiveButton(R.string.trim_upsell_cta_get_pro,
                        (DialogInterface d, int w) -> openProScreen(activity))
                .setNegativeButton(R.string.trim_upsell_cta_later, null)
                .show();
    }

    public static void showBetaGrandfatherWelcomeIfNeeded(FragmentActivity activity,
                                                          EntitlementStore.Snapshot snapshot) {
        if (!isProUiVisible()) {
            return;
        }
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (snapshot == null || !snapshot.isBetaGrandfather()) {
            return;
        }
        if (UserPreferences.wasBetaGrandfatherWelcomed()) {
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.trim_grandfather_title)
                .setMessage(R.string.trim_grandfather_body)
                .setPositiveButton(R.string.trim_grandfather_dismiss, null)
                .setOnDismissListener(d -> UserPreferences.markBetaGrandfatherWelcomed())
                .show();
    }

    /** Launch the Settings → TrimPlayer Pro screen. */
    public static void openProScreen(Context ctx) {
        Intent i = new Intent(ctx, PreferenceActivity.class);
        i.putExtra(PreferenceActivity.OPEN_TRIM_PRO_SCREEN, true);
        ctx.startActivity(i);
    }
}
