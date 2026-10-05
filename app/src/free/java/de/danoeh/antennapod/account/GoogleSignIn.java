package de.danoeh.antennapod.account;

import android.content.Context;

import de.danoeh.antennapod.R;

/**
 * Free-flavour Google sign-in: deliberately unavailable.
 *
 * The free flavour ships no proprietary Google libraries (androidx.credentials'
 * play-services-auth provider and the Google ID library are both Google-only),
 * which is the entry requirement for F-Droid, IzzyOnDroid and the FOSS lists.
 * Email/password sign-in is unaffected and remains the way to use an account
 * here — only the Google button is gone.
 *
 * Keep the public API identical to the `play` twin in
 * app/src/play/java/de/danoeh/antennapod/account/GoogleSignIn.java.
 */
public final class GoogleSignIn {

    /** Receives the result on the main thread. */
    public interface Callback {
        void onIdToken(String idToken);

        void onUnavailable(int messageRes);
    }

    private GoogleSignIn() {
    }

    /** False: callers should hide the Google button entirely in this build. */
    public static boolean isSupported() {
        return false;
    }

    public static void requestIdToken(Context context, String serverClientId, Callback callback) {
        // Never reached while callers honour isSupported(), but answer honestly
        // rather than silently doing nothing if one forgets.
        callback.onUnavailable(R.string.trim_account_google_unavailable);
    }
}
