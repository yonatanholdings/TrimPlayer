package de.danoeh.antennapod.ui.preferences.screen.bugreport;

import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.playback.service.trim.TrimFeedbackClient;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.common.AnimatedFragment;
import de.danoeh.antennapod.ui.preferences.R;
import de.danoeh.antennapod.ui.preferences.databinding.BugReportFragmentBinding;
import de.danoeh.antennapod.ui.preferences.databinding.FeedbackMessageBubbleBinding;
import de.danoeh.antennapod.ui.preferences.databinding.FeedbackThreadItemBinding;

/**
 * Send-feedback / bug-report screen.
 *
 * <p>The user picks a category (bug / feature request / other), writes a title
 * + body, optionally attaches device info + the latest crash log, and the form
 * POSTs to the backend's /feedback endpoint. Replies from the dev are polled
 * via /feedback/threads and surfaced above the form as an inbox.
 *
 * <p>The old copy-to-clipboard / "open in GitHub" affordances are gone — the
 * thread itself is the conversation channel now. The overflow menu still
 * offers "Export logs" so a user (or support) can grab a full logcat dump.
 */
public class BugReportFragment extends AnimatedFragment {
    private static final String TAG = "BugReportFragment";

    private BugReportFragmentBinding viewBinding;
    private BugReportViewModel viewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(BugReportViewModel.class);

        postponeEnterTransition();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = BugReportFragmentBinding.inflate(inflater, container, false);
        return viewBinding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewBinding = null;
    }

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupContextMenu();

        viewModel.getState().observe(getViewLifecycleOwner(), uiState -> {
            bindAttachments(uiState);
            startPostponedEnterTransition();
        });

        viewBinding.sendFeedbackButton.setOnClickListener(v -> submitForm());

        refreshInbox();
    }

    @Override
    public void onStart() {
        super.onStart();

        Objects.requireNonNull(((AppCompatActivity) requireActivity()).getSupportActionBar())
                .setTitle(R.string.report_bug_title);
    }

    // ---------------------------------------------------------------------
    // Attachments — display previews of what the toggles will include.
    // ---------------------------------------------------------------------

    private void bindAttachments(@NonNull BugReportViewModel.UiState uiState) {
        viewBinding.devicePreview.setText(uiState.getEnvironmentInfoWithMarkup());

        BugReportViewModel.CrashLogInfo crash = uiState.getCrashLogInfo();
        if (crash.isAvailable()) {
            String createdAt = uiState.getFormattedCrashLogTimestamp();
            viewBinding.attachCrashSwitch.setText(
                    getString(R.string.feedback_attach_crash) + " · " + createdAt);
            viewBinding.attachCrashSwitch.setVisibility(View.VISIBLE);
            viewBinding.attachCrashSwitch.setChecked(true);
            viewBinding.crashPreview.setText(crash.getContent());
            viewBinding.crashPreview.setVisibility(View.VISIBLE);
            viewBinding.attachCrashSwitch.setOnCheckedChangeListener((b, checked) ->
                    viewBinding.crashPreview.setVisibility(checked ? View.VISIBLE : View.GONE));
        } else {
            viewBinding.attachCrashSwitch.setVisibility(View.GONE);
            viewBinding.crashPreview.setVisibility(View.GONE);
        }

        viewBinding.attachDeviceSwitch.setOnCheckedChangeListener((b, checked) ->
                viewBinding.devicePreview.setVisibility(checked ? View.VISIBLE : View.GONE));
    }

    // ---------------------------------------------------------------------
    // Form submission.
    // ---------------------------------------------------------------------

    private String selectedCategory() {
        int checkedId = viewBinding.categoryChipGroup.getCheckedChipId();
        if (checkedId == R.id.chipFeature) {
            return "feature";
        } else if (checkedId == R.id.chipOther) {
            return "other";
        }
        return "bug";
    }

    private void submitForm() {
        Log.d(TAG, "submitForm: click received");

        // ViewModel does file I/O on a background thread; on a slow boot the
        // user can land on Send before the state arrives. Don't crash — show a
        // snackbar so they know to retry.
        BugReportViewModel.UiState uiState = viewModel.getState().getValue();
        if (uiState == null) {
            Log.w(TAG, "submitForm: uiState not loaded yet");
            Snackbar.make(viewBinding.getRoot(),
                    R.string.feedback_inbox_refreshing, Snackbar.LENGTH_SHORT).show();
            return;
        }

        String title = viewBinding.feedbackTitleInput.getText() == null ? ""
                : viewBinding.feedbackTitleInput.getText().toString().trim();
        String body = viewBinding.feedbackBodyInput.getText() == null ? ""
                : viewBinding.feedbackBodyInput.getText().toString().trim();

        // Clear any stale error first so a successful submit doesn't leave the
        // red underline behind.
        viewBinding.feedbackBodyLayout.setError(null);

        if (body.isEmpty()) {
            Log.d(TAG, "submitForm: empty body, prompting user");
            // setError on the layout (not the inner EditText) — the inner one
            // shows a tiny tooltip that's easy to miss; the layout shows a red
            // helper line below the field. Snackbar is the belt-and-braces.
            viewBinding.feedbackBodyLayout.setError(getString(R.string.feedback_body_required));
            viewBinding.feedbackBodyInput.requestFocus();
            Snackbar.make(viewBinding.getRoot(),
                    R.string.feedback_body_required, Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (title.isEmpty()) {
            // Fall back to first line of the body so the inbox header isn't blank.
            int nl = body.indexOf('\n');
            title = (nl < 0 ? body : body.substring(0, nl)).trim();
            if (title.length() > 80) {
                title = title.substring(0, 80);
            }
        }

        String envJson = viewBinding.attachDeviceSwitch.isChecked()
                ? uiState.getEnvironmentInfoWithMarkup() : null;
        String crashLog = (viewBinding.attachCrashSwitch.getVisibility() == View.VISIBLE
                && viewBinding.attachCrashSwitch.isChecked())
                ? uiState.getCrashInfoWithMarkup() : null;

        String category = selectedCategory();
        Log.d(TAG, "submitForm: category=" + category + " bodyLen=" + body.length()
                + " envAttached=" + (envJson != null) + " crashAttached=" + (crashLog != null));

        viewBinding.sendFeedbackButton.setEnabled(false);
        viewBinding.sendFeedbackButton.setText(R.string.feedback_inbox_refreshing);
        TrimFeedbackClient.submit(requireContext().getApplicationContext(),
                category, title, body, envJson, crashLog,
                new TrimFeedbackClient.SubmitCallback() {
                    @Override
                    public void onSuccess(long threadId) {
                        Log.d(TAG, "submitForm: thread=" + threadId);
                        if (viewBinding == null) {
                            return;
                        }
                        viewBinding.feedbackTitleInput.setText("");
                        viewBinding.feedbackBodyInput.setText("");
                        viewBinding.sendFeedbackButton.setEnabled(true);
                        viewBinding.sendFeedbackButton.setText(R.string.feedback_send);
                        Snackbar.make(viewBinding.getRoot(), R.string.feedback_sent,
                                Snackbar.LENGTH_LONG).show();
                        refreshInbox();
                    }

                    @Override
                    public void onFailure(@Nullable String reason) {
                        Log.w(TAG, "submitForm: failed: " + reason);
                        if (viewBinding == null) {
                            return;
                        }
                        viewBinding.sendFeedbackButton.setEnabled(true);
                        viewBinding.sendFeedbackButton.setText(R.string.feedback_send);
                        // AlertDialog instead of Snackbar so the exception
                        // class name is fully readable. Snackbar truncates to
                        // an ellipsis past ~2 lines when an action is present.
                        String full = getString(R.string.feedback_send_failed);
                        if (reason != null && !reason.isEmpty()) {
                            full = full + "\n\n" + reason;
                        }
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.report_bug_title)
                                .setMessage(full)
                                .setPositiveButton(R.string.confirm_label, null)
                                .show();
                    }
                });
    }

    // ---------------------------------------------------------------------
    // Inbox — polls /feedback/threads and renders one card per thread.
    // ---------------------------------------------------------------------

    private void refreshInbox() {
        viewBinding.inboxProgress.setVisibility(View.VISIBLE);
        viewBinding.inboxEmpty.setVisibility(View.GONE);
        TrimFeedbackClient.fetchThreads(requireContext().getApplicationContext(),
                new TrimFeedbackClient.FetchCallback() {
                    @Override
                    public void onThreads(@NonNull List<TrimClient.FeedbackThread> threads) {
                        if (viewBinding == null) {
                            return;
                        }
                        viewBinding.inboxProgress.setVisibility(View.GONE);
                        renderThreads(threads);
                    }

                    @Override
                    public void onFailure() {
                        if (viewBinding == null) {
                            return;
                        }
                        viewBinding.inboxProgress.setVisibility(View.GONE);
                        // Leave whatever was there alone; just hide the spinner.
                        if (viewBinding.inboxThreadList.getChildCount() == 0) {
                            viewBinding.inboxEmpty.setVisibility(View.VISIBLE);
                        }
                    }
                });
    }

    private void renderThreads(@NonNull List<TrimClient.FeedbackThread> threads) {
        viewBinding.inboxThreadList.removeAllViews();
        if (threads.isEmpty()) {
            viewBinding.inboxEmpty.setVisibility(View.VISIBLE);
            return;
        }
        viewBinding.inboxEmpty.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (TrimClient.FeedbackThread t : threads) {
            FeedbackThreadItemBinding item = FeedbackThreadItemBinding.inflate(
                    inflater, viewBinding.inboxThreadList, false);
            bindThreadItem(item, t, inflater);
            viewBinding.inboxThreadList.addView(item.getRoot());
        }
    }

    private void bindThreadItem(@NonNull FeedbackThreadItemBinding item,
                                @NonNull TrimClient.FeedbackThread thread,
                                @NonNull LayoutInflater inflater) {
        item.threadTitle.setText(thread.title);
        item.threadStatusChip.setText(statusLabel(thread.status));
        item.threadMeta.setText(formatMeta(thread));
        if (thread.unread_for_user > 0) {
            item.threadUnreadDot.setText(R.string.feedback_new_reply);
            item.threadUnreadDot.setVisibility(View.VISIBLE);
        } else {
            item.threadUnreadDot.setVisibility(View.GONE);
        }

        // Render all messages once so the toggle is a pure visibility swap;
        // threads are short, so the cost of inflating up-front is negligible.
        item.threadMessageList.removeAllViews();
        if (thread.messages != null) {
            for (TrimClient.FeedbackMessage m : thread.messages) {
                FeedbackMessageBubbleBinding bubble = FeedbackMessageBubbleBinding.inflate(
                        inflater, item.threadMessageList, false);
                bubble.messageSender.setText(senderLine(m));
                bubble.messageBody.setText(m.body);
                item.threadMessageList.addView(bubble.getRoot());
            }
        }

        item.threadHeader.setOnClickListener(v -> {
            boolean expanded = item.threadMessageList.getVisibility() == View.VISIBLE;
            item.threadMessageList.setVisibility(expanded ? View.GONE : View.VISIBLE);
            if (!expanded && thread.unread_for_user > 0) {
                // Optimistic: clear the badge locally; the server-side ack
                // happens fire-and-forget and the next poll reconciles.
                item.threadUnreadDot.setVisibility(View.GONE);
                int currentUnread = UserPreferences.getFeedbackUnreadCount();
                UserPreferences.setFeedbackUnreadCount(currentUnread - thread.unread_for_user);
                thread.unread_for_user = 0;
                TrimFeedbackClient.markRead(requireContext().getApplicationContext(), thread.id);
            }
        });
    }

    private String statusLabel(@Nullable String status) {
        if ("resolved".equals(status)) {
            return getString(R.string.feedback_status_resolved);
        }
        if ("closed".equals(status)) {
            return getString(R.string.feedback_status_closed);
        }
        return getString(R.string.feedback_status_open);
    }

    private String senderLine(@NonNull TrimClient.FeedbackMessage m) {
        String who = "admin".equals(m.sender)
                ? getString(R.string.feedback_sender_admin)
                : getString(R.string.feedback_sender_user);
        long when = parseIsoMs(m.created_at);
        if (when <= 0) {
            return who;
        }
        CharSequence rel = DateUtils.getRelativeTimeSpanString(
                when, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return who + " · " + rel;
    }

    private String formatMeta(@NonNull TrimClient.FeedbackThread t) {
        int msgs = t.messages == null ? 0 : t.messages.size();
        String category = categoryLabel(t.category);
        long updated = parseIsoMs(t.updated_at);
        String when = updated > 0
                ? DateUtils.getRelativeTimeSpanString(
                        updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                : "";
        return when.isEmpty()
                ? String.format(Locale.getDefault(), "%s · %d", category, msgs)
                : String.format(Locale.getDefault(), "%s · %d · %s", category, msgs, when);
    }

    private String categoryLabel(@Nullable String category) {
        if ("feature".equals(category)) {
            return getString(R.string.feedback_category_feature);
        }
        if ("other".equals(category)) {
            return getString(R.string.feedback_category_other);
        }
        return getString(R.string.feedback_category_bug);
    }

    /** Best-effort ISO-8601 → epoch-ms. Returns 0 when the input isn't parseable
     *  so callers fall back to a relative-time-free label. Uses SimpleDateFormat
     *  to stay under minSdk 23 (java.time.Instant.parse needs API 26). */
    private static long parseIsoMs(@Nullable String iso) {
        if (iso == null || iso.isEmpty()) {
            return 0L;
        }
        // pg serializes as "2026-06-03T12:34:56.789012+00:00" — drop the
        // sub-second precision past millis (java.text can't take 6-digit
        // fractional seconds) and normalize the timezone for SDF.
        String normalized = iso;
        int dot = normalized.indexOf('.');
        if (dot >= 0) {
            int end = dot + 1;
            while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
                end++;
            }
            // Keep up to 3 fractional digits, drop the rest.
            int keep = Math.min(end, dot + 4);
            normalized = normalized.substring(0, keep) + normalized.substring(end);
        }
        // Append Z for trailing "+00:00" → SDF "Z" wants "+0000" style; just
        // try the simplest "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" first.
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
        };
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(p, Locale.US);
                return fmt.parse(normalized).getTime();
            } catch (Exception ignored) {
                // Try the next pattern.
            }
        }
        return 0L;
    }

    // ---------------------------------------------------------------------
    // Overflow menu (Export logs).
    // ---------------------------------------------------------------------

    private void setupContextMenu() {
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.bug_report_options, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.export_logcat) {
                    showExportLogcatDialog();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner());
    }

    private void showExportLogcatDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.export_logs_menu_title);
        builder.setMessage(R.string.confirm_export_log_dialog_message);
        builder.setPositiveButton(R.string.confirm_label, (dialog, which) -> exportLogcat());
        builder.setNegativeButton(R.string.cancel_label, null);
        builder.show();
    }

    private void exportLogcat() {
        try {
            File filename = new File(UserPreferences.getDataFolder(null), "full-logs.txt");
            String cmd = "logcat -d -f " + filename.getAbsolutePath();
            Runtime.getRuntime().exec(cmd);

            try {
                String authority = getString(R.string.provider_authority);
                Uri fileUri = FileProvider.getUriForFile(requireContext(), authority, filename);

                new ShareCompat.IntentBuilder(requireContext())
                        .setType("text/*")
                        .addStream(fileUri)
                        .setChooserTitle(R.string.share_file_label)
                        .startChooser();
            } catch (Exception e) {
                e.printStackTrace();
                Snackbar.make(viewBinding.getRoot(), R.string.log_file_share_exception,
                        Snackbar.LENGTH_LONG).show();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Snackbar.make(viewBinding.getRoot(), e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }
}
