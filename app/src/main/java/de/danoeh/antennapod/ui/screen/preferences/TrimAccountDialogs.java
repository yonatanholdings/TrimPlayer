package de.danoeh.antennapod.ui.screen.preferences;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
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
import androidx.credentials.exceptions.NoCredentialException;
import androidx.preference.Preference;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
    private static final String TAG = "TrimAccountDialogs";

    private TrimAccountDialogs() {
    }

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
                .setNeutralButton(R.string.trim_account_link_watch,
                        (d, w) -> showLinkWatch(context))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Watch pairing, step 1: pick the watch brand. Only Garmin is supported
     *  today — Apple/Samsung rows are visible but disabled ("coming soon"), so
     *  the roadmap is explicit instead of users guessing what works. */
    private static void showLinkWatch(Context context) {
        String[] brands = {
                context.getString(R.string.trim_watch_brand_garmin),
                context.getString(R.string.trim_watch_brand_apple),
                context.getString(R.string.trim_watch_brand_samsung),
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                context, android.R.layout.simple_list_item_1, brands) {
            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return position == 0; // Garmin only
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setEnabled(position == 0); // grey out the coming-soon rows
                return v;
            }
        };
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.trim_account_link_watch)
                .setAdapter(adapter, (d, which) -> {
                    if (which == 0) {
                        showGarminCodeDialog(context);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Watch pairing, step 2 (Garmin): enter the code from the watch's sign-in
     *  screen and approve it against the account (device-link flow — the in-app
     *  equivalent of the web player's /link page, so pairing needs no second
     *  device). */
    private static void showGarminCodeDialog(Context context) {
        TextInputLayout layout = new TextInputLayout(context);
        TextInputEditText input = new TextInputEditText(context);
        input.setHint(R.string.trim_account_link_watch_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setSingleLine(true);
        layout.addView(input);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.trim_account_link_watch)
                .setMessage(R.string.trim_account_link_watch_message)
                .setView(layout)
                .setPositiveButton(R.string.trim_account_link_watch_action, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // Custom click handler (set after show) so a failed attempt keeps the
        // dialog open with an inline error instead of dismissing.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String code = input.getText() == null ? "" : input.getText().toString();
                    v.setEnabled(false);
                    layout.setError(null);
                    Handler main = new Handler(Looper.getMainLooper());
                    new Thread(() -> {
                        String error = TrimAccountManager.approveDevice(code);
                        main.post(() -> {
                            if (error == null) {
                                dialog.dismiss();
                                Toast.makeText(context,
                                        R.string.trim_account_link_watch_success,
                                        Toast.LENGTH_LONG).show();
                            } else {
                                v.setEnabled(true);
                                layout.setError(error);
                            }
                        });
                    }, "trim-device-approve").start();
                }));
        dialog.show();
    }

    private static void showLogin(Context context, Preference pref, boolean startSignup) {
        // Bespoke branded surface (res/layout/dialog_trim_login.xml) hosted in a
        // MaterialAlertDialogBuilder with NO builder buttons — every action lives in
        // the layout, so it reads as a designed login screen rather than a stock
        // AlertDialog. Cancel is handled by tapping outside / back.
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_trim_login, null, false);
        ImageView logo = content.findViewById(R.id.login_logo);
        TextView title = content.findViewById(R.id.login_title);
        TextView subtitle = content.findViewById(R.id.login_subtitle);
        MaterialButton googleBtn = content.findViewById(R.id.login_btn_google);
        TextInputEditText email = content.findViewById(R.id.login_email);
        TextInputEditText password = content.findViewById(R.id.login_password);
        TextInputLayout emailLayout = content.findViewById(R.id.login_email_layout);
        TextInputLayout passwordLayout = content.findViewById(R.id.login_password_layout);
        TextView errorBanner = content.findViewById(R.id.login_error);
        MaterialButton primaryBtn = content.findViewById(R.id.login_btn_primary);
        MaterialButton secondaryBtn = content.findViewById(R.id.login_btn_secondary);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(content)
                .create();

        // Mode is toggled in-place (login <-> signup) so we never re-inflate or
        // re-run the entrance animation when the user switches.
        boolean[] signup = { startSignup };
        Runnable bindMode = () -> {
            boolean s = signup[0];
            title.setText(s ? R.string.trim_account_welcome_signup_title
                    : R.string.trim_account_welcome_title);
            subtitle.setText(s ? R.string.trim_account_signup_sub : R.string.trim_account_login_sub);
            primaryBtn.setText(s ? R.string.trim_account_action_signup
                    : R.string.trim_account_action_login);
            secondaryBtn.setText(s ? R.string.trim_account_switch_to_login
                    : R.string.trim_account_switch_to_signup);
            errorBanner.setVisibility(View.GONE);
            emailLayout.setError(null);
            passwordLayout.setError(null);
        };
        bindMode.run();

        googleBtn.setOnClickListener(v -> startGoogleSignIn(context, pref, dialog));
        primaryBtn.setOnClickListener(v -> {
            errorBanner.setVisibility(View.GONE);
            authenticate(context, pref, dialog, signup[0],
                    email.getText().toString().trim(), password.getText().toString(), primaryBtn);
        });
        secondaryBtn.setOnClickListener(v -> {
            signup[0] = !signup[0];
            bindMode.run();
        });

        dialog.setOnShowListener(d -> animateIn(context, content, logo));
        dialog.show();
    }

    /** Subtle, single-shot entrance: the card fades + scales in, the hero logo
     *  drifts down into place just after. GPU-cheap (alpha/scale/translation only),
     *  no looping animation — battery- and ANR-safe. */
    private static void animateIn(Context context, View content, View logo) {
        content.setAlpha(0f);
        content.setScaleX(0.96f);
        content.setScaleY(0.96f);
        content.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        float dy = 8 * context.getResources().getDisplayMetrics().density;
        logo.setAlpha(0f);
        logo.setTranslationY(-dy);
        logo.animate().alpha(1f).translationY(0f)
                .setStartDelay(60).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    /** Show an auth error inside the dialog (persists, unlike a toast). Falls back
     *  to a toast if the banner isn't present (e.g. dialog already torn down). */
    private static void showError(AlertDialog dialog, String message) {
        TextView banner = dialog.findViewById(R.id.login_error);
        if (banner != null) {
            banner.setText(message);
            banner.setVisibility(View.VISIBLE);
        } else {
            Toast.makeText(dialog.getContext(), message, Toast.LENGTH_LONG).show();
        }
    }

    // --- Native Google sign-in (Credential Manager) ---------------------------

    private static void startGoogleSignIn(Context context, Preference pref, AlertDialog dialog) {
        Handler main = new Handler(Looper.getMainLooper());
        // Resolve the backend's OAuth Web client id off the main thread.
        new Thread(() -> {
            String serverClientId = TrimAccountManager.fetchGoogleClientId();
            main.post(() -> {
                if (serverClientId == null || serverClientId.isEmpty()) {
                    // Backend /auth/config returned no google_client_id (or 404).
                    Log.w(TAG, "Google sign-in unavailable: backend returned no client id");
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
        // Button-triggered flow: use GetSignInWithGoogleOption (the explicit
        // "Sign in with Google" button option), NOT GetGoogleIdOption — the
        // latter is the One-Tap/auto-select style that throws
        // NoCredentialException on first use or after a prior dismissal.
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
                        handleGoogleCredential(context, pref, dialog, result);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        // Log the real cause — without this, every failure looks
                        // identical and is impossible to diagnose from the field.
                        Log.w(TAG, "Google credential request failed: "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
                        if (e instanceof NoCredentialException) {
                            // No Google account on the device (or the user has
                            // none that can be offered) — actionable for the user.
                            Toast.makeText(context, R.string.trim_account_google_no_account,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            // Real failure (config/SHA-1 mismatch, cancellation,
                            // transient errors). Cancellation is benign but rare
                            // enough that a toast is acceptable.
                            Toast.makeText(context, R.string.trim_account_google_failed,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    private static void handleGoogleCredential(Context context, Preference pref,
                                               AlertDialog dialog, GetCredentialResponse response) {
        Credential credential = response.getCredential();
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            Log.w(TAG, "Unexpected credential type from Credential Manager: " + credential.getType());
            Toast.makeText(context, R.string.trim_account_google_failed, Toast.LENGTH_LONG).show();
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
                    showError(dialog, error);
                }
            });
        }).start();
    }

    private static void authenticate(Context context, Preference pref, AlertDialog dialog,
                                     boolean signupMode, String emailText, String passwordText,
                                     MaterialButton primaryBtn) {
        primaryBtn.setEnabled(false); // guard against double-submit while the call runs
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = signupMode
                    ? TrimAccountManager.signup(emailText, passwordText)
                    : TrimAccountManager.login(emailText, passwordText);
            main.post(() -> {
                primaryBtn.setEnabled(true);
                if (error == null) {
                    dialog.dismiss();
                    refreshSummary(pref);
                    kickSync(context);
                    Toast.makeText(context, R.string.trim_account_login_success,
                            Toast.LENGTH_SHORT).show();
                } else {
                    showError(dialog, error);
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
