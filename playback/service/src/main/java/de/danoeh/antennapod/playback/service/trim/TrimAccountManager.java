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
 * <p>On success the session token + email are persisted via {@link UserPreferences}
 * and {@link de.danoeh.antennapod.storage.preferences.UserPreferences#isTrimAccountLoggedIn()}
 * flips to true, which is all {@code TrimSyncWorker} needs to begin syncing.
 */
public final class TrimAccountManager {
    private static final String TAG = "TrimAccount";

    private TrimAccountManager() {
    }

    /**
     * @return null on success, else a human-readable error message.
     */
    public static String login(String email, String password) {
        return authenticate(false, email, password);
    }

    /**
     * @return null on success, else a human-readable error message.
     */
    public static String signup(String email, String password) {
        return authenticate(true, email, password);
    }

    public static void logout() {
        UserPreferences.clearTrimAccount();
    }

    /** The backend's Google OAuth Web client id (serverClientId for native
     *  Credential Manager sign-in), or null if Google sign-in isn't configured.
     *  Blocking — call off the main thread. */
    public static String fetchGoogleClientId() {
        try {
            Response<TrimClient.AuthConfig> resp = TrimClient.getInstance().authConfig().execute();
            if (resp.isSuccessful() && resp.body() != null) {
                return resp.body().google_client_id;
            }
        } catch (IOException e) {
            Log.w(TAG, "fetchGoogleClientId failed: " + e.getMessage());
        }
        return null;
    }

    /** Exchange a Google ID token for a session and persist it.
     *  @return null on success, else a human-readable error message. Blocking. */
    public static String loginWithGoogle(String idToken) {
        String clientId = UserPreferences.getOrCreateTrimClientId();
        try {
            Response<TrimClient.AuthResponse> resp =
                    TrimClient.getInstance().authGoogle(idToken, clientId).execute();
            if (resp.isSuccessful() && resp.body() != null && resp.body().token != null) {
                UserPreferences.setTrimAccount(resp.body().token, resp.body().email);
                return null;
            }
            if (resp.code() == 503) {
                return "Google sign-in isn't available right now.";
            }
            if (resp.code() == 401) {
                return "Google sign-in was rejected. Please try again.";
            }
            return "Google sign-in failed (" + resp.code() + ").";
        } catch (IOException e) {
            Log.w(TAG, "google auth network failure: " + e.getMessage());
            return "Network error. Check your connection and try again.";
        }
    }

    /** Approve the pairing code shown on a watch's sign-in screen, binding the
     *  watch to this account (device-link flow — same as the web player's /link
     *  page). Blocking.
     *  @return null on success, else a human-readable error message. */
    public static String approveDevice(String userCode) {
        String token = UserPreferences.getTrimAccountToken();
        if (token == null) {
            return "Sign in to your TrimPlayer account first.";
        }
        String code = (userCode == null) ? "" : userCode.trim();
        if (code.isEmpty()) {
            return "Enter the code shown on your watch.";
        }
        try {
            Response<TrimClient.StatusResponse> resp =
                    TrimClient.getInstance().deviceApprove("Bearer " + token, code).execute();
            if (resp.isSuccessful()) {
                return null;
            }
            if (resp.code() == 404 || resp.code() == 400) {
                return "That code wasn't recognized — check it on the watch and try again.";
            }
            if (resp.code() == 401) {
                return "Your session expired. Log in again, then retry.";
            }
            return "Linking failed (" + resp.code() + "). Please try again.";
        } catch (IOException e) {
            Log.w(TAG, "device approve network failure: " + e.getMessage());
            return "Network error. Check your connection and try again.";
        }
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
