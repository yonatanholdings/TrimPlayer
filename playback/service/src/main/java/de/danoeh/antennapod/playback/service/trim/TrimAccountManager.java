package de.danoeh.antennapod.playback.service.trim;

import android.util.Log;

import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.io.IOException;

import retrofit2.Response;

/**
 * Thin façade over the account auth endpoints for the TrimPlayer web/phone sync
 * account. Blocking calls — invoke from a background thread (the login screen
 * runs these off the main thread and stores the session on success).
 *
 * On success the session token + email are persisted via {@link UserPreferences}
 * and {@link de.danoeh.antennapod.storage.preferences.UserPreferences#isTrimAccountLoggedIn()}
 * flips to true, which is all {@code TrimSyncWorker} needs to begin syncing.
 */
public final class TrimAccountManager {
    private static final String TAG = "TrimAccount";

    private TrimAccountManager() { }

    /** @return null on success, else a human-readable error message. */
    public static String login(String email, String password) {
        return authenticate(false, email, password);
    }

    /** @return null on success, else a human-readable error message. */
    public static String signup(String email, String password) {
        return authenticate(true, email, password);
    }

    public static void logout() {
        UserPreferences.clearTrimAccount();
    }

    private static String authenticate(boolean isSignup, String email, String password) {
        String clientId = UserPreferences.getOrCreateTrimClientId();
        try {
            Response<TrimClient.AuthResponse> resp = (isSignup
                    ? TrimClient.getInstance().signup(email, password, clientId)
                    : TrimClient.getInstance().login(email, password, clientId)).execute();
            if (resp.isSuccessful() && resp.body() != null && resp.body().token != null) {
                UserPreferences.setTrimAccount(resp.body().token, resp.body().email);
                return null;
            }
            if (resp.code() == 401) {
                return "Incorrect email or password.";
            }
            if (resp.code() == 409) {
                return "That email is already registered — try logging in.";
            }
            return "Sign-in failed (" + resp.code() + "). Please try again.";
        } catch (IOException e) {
            Log.w(TAG, "auth network failure: " + e.getMessage());
            return "Network error. Check your connection and try again.";
        }
    }
}
