package de.danoeh.antennapod.ui.preferences.screen.bugreport;

import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
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
import de.danoeh.antennapod.ui.preferences.databinding.FeedbackCategoryCardBinding;
import de.danoeh.antennapod.ui.preferences.databinding.FeedbackMessageBubbleBinding;
import de.danoeh.antennapod.ui.preferences.databinding.FeedbackThreadItemBinding;

/**
 * Talk-to-us / feedback screen.
 *
 * <p>The redesign treats this as a real conversation surface, not a bug report
 * form. Layout, top to bottom:
 *
 * <pre>
 *   ┌─────────────────────────────────────────┐
 *   │  What's on your mind?                   │ ← hero
 *   │  A real human reads every message.      │
 *   │                                         │
 *   │  YOUR CONVERSATIONS         (refreshing)│ ← inbox, hidden on first run
 *   │  ┃ ▌  Skip-silence cuts intro    ●     │
 *   │  ┃ ▌  🐞 Bug · 2 msgs · 3m              │
 *   │                                         │
 *   │  PICK ONE                               │
 *   │  ┌───────┐  ┌───────┐  ┌───────┐         │ ← category cards
 *   │  │  🐞   │  │  💡   │  │  💬   │         │
 *   │  │  Bug  │  │ Idea  │  │ Other │         │
 *   │  └───────┘  └───────┘  └───────┘         │
 *   │                                         │
 *   │  YOUR MESSAGE                           │ ← body field is the hero
 *   │  ┌─────────────────────────────────┐   │
 *   │  │                                 │   │
 *   │  └─────────────────────────────────┘   │
 *   │                                         │
 *   │  SEND ALONG                          ▼  │ ← collapsed extras
 *   ├─────────────────────────────────────────┤
 *   │ [          Send                ]        │ ← pinned CTA
 *   └─────────────────────────────────────────┘
 * </pre>
 *
 * <p>The old chip group / title field / always-visible device-info preview are
 * gone. The selected category is held in a field (no chip checked-id), the
 * title is auto-derived from the first body line on submit, and the device /
 * crash-log attachments live behind a "Send along" expander so the form
 * doesn't open with dev noise.
 */
public class BugReportFragment extends AnimatedFragment {
    private static final String TAG = "BugReportFragment";

    private static final String CATEGORY_BUG = "bug";
    private static final String CATEGORY_FEATURE = "feature";
    private static final String CATEGORY_OTHER = "other";

    // Accent colors keyed off the category. Picked for legibility against both
    // the surface-container background of the inbox card AND the secondary
    // container background of the status pill — saturated enough to read as
    // "this one's a bug" without becoming louder than the title text.
    private static final int CATEGORY_COLOR_BUG = 0xFFE53935;       // material red 600
    private static final int CATEGORY_COLOR_FEATURE = 0xFF3B82F6;   // sky 500
    private static final int CATEGORY_COLOR_OTHER = 0xFF6B7280;     // slate 500

    private BugReportFragmentBinding viewBinding;
    private BugReportViewModel viewModel;
    private String selectedCategory = CATEGORY_BUG;
    private boolean extrasExpanded = false;

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
        setupCategoryCards();
        setupExtrasExpander();

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
    // Category picker — 3 tap cards instead of filter chips.
    // ---------------------------------------------------------------------

    private void setupCategoryCards() {
        bindCategoryCard(viewBinding.categoryBug, "🐞",
                R.string.feedback_category_bug, R.string.feedback_category_bug_sub,
                CATEGORY_BUG);
        bindCategoryCard(viewBinding.categoryFeature, "💡",
                R.string.feedback_category_feature, R.string.feedback_category_feature_sub,
                CATEGORY_FEATURE);
        bindCategoryCard(viewBinding.categoryOther, "💬",
                R.string.feedback_category_other, R.string.feedback_category_other_sub,
                CATEGORY_OTHER);
        applyCategorySelection();
    }

    private void bindCategoryCard(@NonNull FeedbackCategoryCardBinding card,
                                  @NonNull String emoji,
                                  int labelRes, int sublabelRes,
                                  @NonNull String value) {
        card.categoryIcon.setText(emoji);
        card.categoryLabel.setText(labelRes);
        card.categorySublabel.setText(sublabelRes);
        card.getRoot().setOnClickListener(v -> {
            if (!value.equals(selectedCategory)) {
                selectedCategory = value;
                applyCategorySelection();
            }
        });
    }

    private void applyCategorySelection() {
        viewBinding.categoryBug.getRoot()
                .setSelected(CATEGORY_BUG.equals(selectedCategory));
        viewBinding.categoryFeature.getRoot()
                .setSelected(CATEGORY_FEATURE.equals(selectedCategory));
        viewBinding.categoryOther.getRoot()
                .setSelected(CATEGORY_OTHER.equals(selectedCategory));
    }

    // ---------------------------------------------------------------------
    // "Send along" expander — collapses the attach toggles.
    // ---------------------------------------------------------------------

    private void setupExtrasExpander() {
        viewBinding.extrasHeader.setOnClickListener(v -> {
            extrasExpanded = !extrasExpanded;
            viewBinding.extrasBody.setVisibility(extrasExpanded ? View.VISIBLE : View.GONE);
            viewBinding.extrasChevron.animate()
                    .rotation(extrasExpanded ? 180f : 0f)
                    .setDuration(180)
                    .start();
        });
    }

    // ---------------------------------------------------------------------
    // Attachments — wire toggles + show/hide crash row depending on whether
    // a stacktrace is on record.
    // ---------------------------------------------------------------------

    private void bindAttachments(@NonNull BugReportViewModel.UiState uiState) {
        // devicePreview / crashPreview remain in the layout as hidden 0-sized
        // views so the existing binding fields don't error; we don't render
        // their content anywhere in the new design.
        viewBinding.devicePreview.setText(uiState.getEnvironmentInfoWithMarkup());

        BugReportViewModel.CrashLogInfo crash = uiState.getCrashLogInfo();
        if (crash.isAvailable()) {
            viewBinding.crashRow.setVisibility(View.VISIBLE);
            String when = uiState.getFormattedCrashLogTimestamp();
            viewBinding.crashSubtitle.setText(
                    getString(R.string.feedback_attach_crash_sub) + " · " + when);
            viewBinding.attachCrashSwitch.setChecked(true);
            viewBinding.crashPreview.setText(crash.getContent());
        } else {
            viewBinding.crashRow.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------------
    // Form submission.
    // ---------------------------------------------------------------------

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

        viewBinding.feedbackBodyLayout.setError(null);

        if (body.isEmpty()) {
            Log.d(TAG, "submitForm: empty body, prompting user");
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
        String crashLog = (viewBinding.crashRow.getVisibility() == View.VISIBLE
                && viewBinding.attachCrashSwitch.isChecked())
                ? uiState.getCrashInfoWithMarkup() : null;

        Log.d(TAG, "submitForm: category=" + selectedCategory + " bodyLen=" + body.length()
                + " envAttached=" + (envJson != null) + " crashAttached=" + (crashLog != null));

        viewBinding.sendFeedbackButton.setEnabled(false);
        viewBinding.sendFeedbackButton.setText(R.string.feedback_sending);
        TrimFeedbackClient.submit(requireContext().getApplicationContext(),
                selectedCategory, title, body, envJson, crashLog,
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
                        // Leave whatever was there alone; the next refresh will
                        // reconcile. Don't flip to the empty state mid-session.
                    }
                });
    }

    private void renderThreads(@NonNull List<TrimClient.FeedbackThread> threads) {
        viewBinding.inboxThreadList.removeAllViews();
        if (threads.isEmpty()) {
            viewBinding.inboxHeaderRow.setVisibility(View.GONE);
            viewBinding.inboxBottomGap.setVisibility(View.GONE);
            viewBinding.inboxEmpty.setVisibility(View.GONE);
            return;
        }
        viewBinding.inboxHeaderRow.setVisibility(View.VISIBLE);
        viewBinding.inboxBottomGap.setVisibility(View.VISIBLE);
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
        item.threadColorBar.setBackgroundColor(categoryColor(thread.category));
        item.threadStatusChip.setText(statusBadge(thread.status));
        item.threadMeta.setText(formatMeta(thread));
        item.threadUnreadDot.setVisibility(
                thread.unread_for_user > 0 ? View.VISIBLE : View.GONE);

        // Render all messages once so the expand toggle is a pure visibility
        // swap; threads are short so the cost of inflating up-front is fine.
        item.threadMessageList.removeAllViews();
        if (thread.messages != null) {
            for (TrimClient.FeedbackMessage m : thread.messages) {
                FeedbackMessageBubbleBinding bubble = FeedbackMessageBubbleBinding.inflate(
                        inflater, item.threadMessageList, false);
                bindBubble(bubble, m);
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

    /** Render one message as either a user (right, primary tint) bubble or a
     *  dev (left, neutral) bubble — flipping the row's gravity + the bubble's
     *  background drawable + the leading "sender · time" label alignment. */
    private void bindBubble(@NonNull FeedbackMessageBubbleBinding bubble,
                            @NonNull TrimClient.FeedbackMessage m) {
        bubble.messageSender.setText(senderLine(m));
        bubble.messageBody.setText(m.body);
        boolean isAdmin = "admin".equals(m.sender);
        int gravity = isAdmin ? Gravity.START : Gravity.END;
        bubble.messageRow.setGravity(gravity);
        bubble.messageBody.setBackgroundResource(
                isAdmin ? R.drawable.feedback_bubble_admin : R.drawable.feedback_bubble_user);
        bubble.messageSender.setGravity(gravity);
    }

    private int categoryColor(@Nullable String category) {
        if ("feature".equals(category)) {
            return CATEGORY_COLOR_FEATURE;
        }
        if ("other".equals(category)) {
            return CATEGORY_COLOR_OTHER;
        }
        return CATEGORY_COLOR_BUG;
    }

    /** Tiny status pill text. The leading "●" doubles as a visual indicator
     *  without forcing per-status background colors that fight the theme. */
    private String statusBadge(@Nullable String status) {
        return "●  " + statusLabel(status).toUpperCase(Locale.getDefault());
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
        String emoji = categoryEmoji(t.category);
        String label = categoryLabel(t.category);
        long updated = parseIsoMs(t.updated_at);
        String when = updated > 0
                ? DateUtils.getRelativeTimeSpanString(
                        updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                : "";
        String msgsLabel = msgs == 1 ? "1 message" : msgs + " messages";
        return when.isEmpty()
                ? String.format(Locale.getDefault(), "%s %s · %s", emoji, label, msgsLabel)
                : String.format(Locale.getDefault(), "%s %s · %s · %s",
                        emoji, label, msgsLabel, when);
    }

    private String categoryEmoji(@Nullable String category) {
        if ("feature".equals(category)) {
            return "💡";
        }
        if ("other".equals(category)) {
            return "💬";
        }
        return "🐞";
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
        String normalized = iso;
        int dot = normalized.indexOf('.');
        if (dot >= 0) {
            int end = dot + 1;
            while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
                end++;
            }
            int keep = Math.min(end, dot + 4);
            normalized = normalized.substring(0, keep) + normalized.substring(end);
        }
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
