package de.danoeh.antennapod.account;

import android.content.Context;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executor;

import de.danoeh.antennapod.R;

/**
 * Play-flavour native Google sign-in (Credential Manager + Google ID).
 *
 * This class is the ONLY place in the app that touches androidx.credentials and
 * the Google ID library, so the `free` flavour can be built with no proprietary
 * Google dependency — the entry requirement for F-Droid, IzzyOnDroid and the FOSS
 * app lists. Its twin in app/src/free/ must keep the same public API; the caller
 * (TrimAccountDialogs) is flavour-agnostic.
 *
 * It deliberately stops at the ID token: verifying it, calling the backend and
 * updating the UI all stay in the caller, so the flavour split stays small.
 */
public final class GoogleSignIn {
    private static final String TAG = "GoogleSignIn";

    /** Receives the result on the main thread. */
    public interface Callback {
        /** A Google ID token for the chosen account. POST it to the backend. */
        void onIdToken(String idToken);

        /** Nothing usable came back. {@code messageRes} is user-presentable. */
        void onUnavailable(int messageRes);
    }

    private GoogleSignIn() {
    }

    /** True when this build can offer Google sign-in at all. */
    public static boolean isSupported() {
        return true;
    }

    public static void requestIdToken(Context context, String serverClientId, Callback callback) {
        // Button-triggered flow: GetSignInWithGoogleOption (the explicit "Sign in
        // with Google" button option), NOT GetGoogleIdOption — the latter is the
        // One-Tap/auto-select style that throws NoCredentialException on first use
        // or after a prior dismissal.
        GetSignInWithGoogleOption option =
                new GetSignInWithGoogleOption.Builder(serverClientId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();
        CredentialManager credentialManager = CredentialManager.create(context);
        Executor mainExecutor = ContextCompat.getMainExecutor(context);
        credentialManager.getCredentialAsync(context, request, null, mainExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        Credential credential = result.getCredential();
                        if (!(credential instanceof CustomCredential)
                                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                        .equals(credential.getType())) {
                            Log.w(TAG, "Unexpected credential type: " + credential.getType());
                            callback.onUnavailable(R.string.trim_account_google_failed);
                            return;
                        }
                        callback.onIdToken(GoogleIdTokenCredential
                                .createFrom(((CustomCredential) credential).getData())
                                .getIdToken());
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        // Log the real cause — without this, every failure looks
                        // identical and is impossible to diagnose from the field.
                        Log.w(TAG, "Google credential request failed: "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
                        callback.onUnavailable(e instanceof NoCredentialException
                                // No Google account on the device — actionable.
                                ? R.string.trim_account_google_no_account
                                // Config/SHA-1 mismatch, cancellation, transient.
                                : R.string.trim_account_google_failed);
                    }
                });
    }
}
