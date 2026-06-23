package de.danoeh.antennapod.ui.screen.preferences;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.preference.Preference;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.TrimSyncWorker;
import de.danoeh.antennapod.playback.service.trim.TrimAccountManager;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.concurrent.Executor;

/**
 * Login / logout dialogs for the TrimPlayer sync account, driven from the
 * Settings entry ({@code prefTrimAccount}). Auth runs off the main thread via
 * {@link TrimAccountManager}; on success the {@link TrimSyncWorker} is kicked
 * immediately so the user's library reaches the web player without waiting for
 * the periodic run.
 */
public final class TrimAccountDialogs {
    private TrimAccountDialogs() { }

    /** Entry point: shows login when logged out, or an account/logout sheet when
     *  logged in. {@code pref} summary is refreshed to reflect the new state. */
    public static void show(Context context, Preference pref) {
        if (UserPreferences.isTrimAccountLoggedIn()) {
            showAccount(context, pref);
        } else {
            showLogin(context, pref, false);
        }
    }

    private static void showAccount(Context context, Preference pref) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.trim_account_title)
                .setMessage(context.getString(R.string.trim_account_pref_summary_logged_in,
                        UserPreferences.getTrimAccountEmail()))
                .setPositiveButton(R.string.trim_account_logout, (d, w) -> {
                    TrimAccountManager.logout();
                    refreshSummary(pref);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showLogin(Context context, Preference pref, boolean signupMode) {
        int pad = Math.round(context.getResources().getDisplayMetrics().density * 20);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, 0);

        // Native Google sign-in. Always shown; if the backend hasn't enabled it,
        // the tap reports "not available" rather than the button being hidden
        // behind a pre-dialog network check.
        Button googleBtn = new Button(context);
        googleBtn.setText(R.string.trim_account_google);
        googleBtn.setAllCaps(false);
        layout.addView(googleBtn);

        TextView divider = new TextView(context);
        divider.setText(R.string.trim_account_or);
        divider.setGravity(Gravity.CENTER);
        divider.setPadding(0, pad / 2, 0, pad / 4);
        divider.setAlpha(0.6f);
        layout.addView(divider);

        EditText email = new EditText(context);
        email.setHint(R.string.trim_account_email_hint);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        layout.addView(email);

        EditText password = new EditText(context);
        password.setHint(R.string.trim_account_password_hint);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(password);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(signupMode ? R.string.trim_account_signup_title
                        : R.string.trim_account_login_title)
                .setView(layout)
                .setPositiveButton(signupMode ? R.string.trim_account_action_signup
                        : R.string.trim_account_action_login, null) // overridden below
                .setNeutralButton(signupMode ? R.string.trim_account_switch_to_login
                        : R.string.trim_account_switch_to_signup, (d, w) ->
                        showLogin(context, pref, !signupMode))
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        googleBtn.setOnClickListener(v -> startGoogleSignIn(context, pref, dialog));

        // Override the positive button so a failed login keeps the dialog open.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> authenticate(context, pref, dialog, signupMode,
                        email.getText().toString().trim(), password.getText().toString())));
        dialog.show();
    }

    // --- Native Google sign-in (Credential Manager) ---------------------------

    private static void startGoogleSignIn(Context context, Preference pref, AlertDialog dialog) {
        Handler main = new Handler(Looper.getMainLooper());
        // Resolve the backend's OAuth Web client id off the main thread.
        new Thread(() -> {
            String serverClientId = TrimAccountManager.fetchGoogleClientId();
            main.post(() -> {
                if (serverClientId == null || serverClientId.isEmpty()) {
                    Toast.makeText(context, R.string.trim_account_google_unavailable,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                requestGoogleCredential(context, pref, dialog, serverClientId);
            });
        }).start();
    }

    private static void requestGoogleCredential(Context context, Preference pref,
                                                AlertDialog dialog, String serverClientId) {
        GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false) // let the user pick any account
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();
        CredentialManager credentialManager = CredentialManager.create(context);
        Executor mainExecutor = ContextCompat.getMainExecutor(context);
        credentialManager.getCredentialAsync(context, request, null, mainExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleGoogleCredential(context, pref, dialog, result);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        // Includes the user dismissing the account picker — stay quiet
                        // on cancellation-like errors but surface real failures.
                        Toast.makeText(context, R.string.trim_account_google_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private static void handleGoogleCredential(Context context, Preference pref,
                                               AlertDialog dialog, GetCredentialResponse response) {
        Credential credential = response.getCredential();
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            Toast.makeText(context, R.string.trim_account_google_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        String idToken = GoogleIdTokenCredential
                .createFrom(((CustomCredential) credential).getData()).getIdToken();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = TrimAccountManager.loginWithGoogle(idToken);
            main.post(() -> {
                if (error == null) {
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    refreshSummary(pref);
                    kickSync(context);
                    Toast.makeText(context, R.string.trim_account_login_success,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static void authenticate(Context context, Preference pref, AlertDialog dialog,
                                     boolean signupMode, String emailText, String passwordText) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = signupMode
                    ? TrimAccountManager.signup(emailText, passwordText)
                    : TrimAccountManager.login(emailText, passwordText);
            main.post(() -> {
                if (error == null) {
                    dialog.dismiss();
                    refreshSummary(pref);
                    kickSync(context);
                    Toast.makeText(context, R.string.trim_account_login_success,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static void refreshSummary(Preference pref) {
        if (pref == null) {
            return;
        }
        if (UserPreferences.isTrimAccountLoggedIn()) {
            pref.setSummary(pref.getContext().getString(
                    R.string.trim_account_pref_summary_logged_in,
                    UserPreferences.getTrimAccountEmail()));
        } else {
            pref.setSummary(R.string.trim_account_pref_summary_logged_out);
        }
    }

    private static void kickSync(Context context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
                "trimAccountSyncNow", ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(TrimSyncWorker.class).build());
    }
}
