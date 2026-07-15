package de.danoeh.antennapod.ui.screen.preferences;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import de.danoeh.antennapod.GarminWatchProgressEvent;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.TrimSyncWorker;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.playback.service.trim.TrimAccountManager;
import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.playback.service.trim.TrimSegmentCache;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        // The first row reflects the real link state, so look it up first.
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            TrimClient.Device watch = TrimAccountManager.linkedWatch();
            main.post(() -> showAccountMenu(context, pref, watch));
        }, "trim-account-devices").start();
    }

    private static void showAccountMenu(Context context, Preference pref,
                                        TrimClient.Device watch) {
        String watchRow = (watch == null)
                ? context.getString(R.string.trim_account_link_watch)
                : context.getString(R.string.trim_account_watch_linked, shortDate(watch.linked_at));
        String[] items = {
                watchRow,
                context.getString(R.string.trim_account_watch_episodes),
                context.getString(R.string.trim_account_pull_watch_progress),
                context.getString(R.string.trim_account_logout),
        };
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.trim_account_pref_summary_logged_in,
                        UserPreferences.getTrimAccountEmail()))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        if (watch == null) {
                            showLinkWatch(context);
                        } else {
                            confirmUnlinkWatch(context, watch);
                        }
                    } else if (which == 1) {
                        showWatchEpisodes(context);
                    } else if (which == 2) {
                        pullWatchProgress(context);
                    } else {
                        TrimAccountManager.logout();
                        refreshSummary(pref);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "2026-07-03T16:08:09+00:00" -> "2026-07-03" (readable without java.time,
     *  which needs API 26 / desugaring). */
    private static String shortDate(String iso) {
        return (iso != null && iso.length() >= 10) ? iso.substring(0, 10) : "";
    }

    private static void confirmUnlinkWatch(Context context, TrimClient.Device watch) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.trim_account_unlink_watch_title)
                .setMessage(R.string.trim_account_unlink_watch_message)
                .setPositiveButton(R.string.trim_account_unlink, (d, w) -> {
                    Handler main = new Handler(Looper.getMainLooper());
                    new Thread(() -> {
                        String error = TrimAccountManager.unlinkDevice(watch.client_id);
                        main.post(() -> Toast.makeText(context,
                                error == null
                                        ? context.getString(R.string.trim_account_unlinked)
                                        : error,
                                Toast.LENGTH_LONG).show());
                    }, "trim-unlink-watch").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Ask the watch (over BLE, via Garmin Connect) to transmit its buffered
     *  listen progress now instead of waiting for its next pause/stop/sync
     *  trigger. The dialog narrates each stage — sending, delivered (or exactly
     *  why not), then the applied result — so a hiccup is diagnosable instead
     *  of a silent wait. The reply lands through TrimGarminWatchSync, which
     *  posts a {@link GarminWatchProgressEvent} once the library is updated. */
    private static void pullWatchProgress(Context context) {
        TextView message = new TextView(context);
        int pad = (int) (24 * context.getResources().getDisplayMetrics().density);
        message.setPadding(pad, pad / 2, pad, 0);
        message.setText(R.string.trim_account_pull_watch_progress_sending);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.trim_account_pull_watch_progress)
                .setView(message)
                .setNegativeButton(R.string.close_label, null)
                .create();

        Handler main = new Handler(Looper.getMainLooper());
        Runnable timeout = () -> message.setText(
                R.string.trim_account_pull_watch_progress_timeout);
        // The watch answers with a BURST of documents (one per buffered progress
        // state, then an empty forced reply). Accumulate across events and never
        // let a trailing empty/failed doc downgrade an already-shown positive
        // count — it made the dialog end on "none received" right after real
        // progress was applied.
        final int[] totalApplied = {0};
        Object subscriber = new Object() {
            @Subscribe(threadMode = ThreadMode.MAIN)
            public void onEventMainThread(GarminWatchProgressEvent event) {
                main.removeCallbacks(timeout);
                if (event.appliedCount > 0) {
                    totalApplied[0] += event.appliedCount;
                    message.setText(context.getResources().getQuantityString(
                            R.plurals.trim_account_pull_watch_progress_received,
                            totalApplied[0], totalApplied[0]));
                } else if (totalApplied[0] > 0) {
                    return; // keep the positive result on screen
                } else if (event.appliedCount == 0) {
                    message.setText(R.string.trim_account_pull_watch_progress_none);
                } else {
                    message.setText(R.string.trim_account_pull_watch_progress_failed);
                }
            }
        };
        EventBus.getDefault().register(subscriber);
        dialog.setOnDismissListener(d -> {
            main.removeCallbacks(timeout);
            EventBus.getDefault().unregister(subscriber);
        });

        de.danoeh.antennapod.garmin.GarminCompanionManager.requestProgressFlush(result ->
                main.post(() -> {
                    switch (result) {
                        case de.danoeh.antennapod.garmin.GarminCompanionManager.SEND_DELIVERED:
                            message.setText(R.string.trim_account_pull_watch_progress_delivered);
                            // The watch got the request — only now is a
                            // no-reply timeout meaningful.
                            main.postDelayed(timeout, 30_000);
                            break;
                        case de.danoeh.antennapod.garmin.GarminCompanionManager.SEND_NO_WATCH:
                            message.setText(R.string.trim_account_pull_watch_progress_no_watch);
                            break;
                        case de.danoeh.antennapod.garmin.GarminCompanionManager.SEND_UNAVAILABLE:
                            message.setText(R.string.trim_account_pull_watch_progress_unavailable);
                            break;
                        default:
                            message.setText(R.string.trim_account_pull_watch_progress_not_delivered);
                            break;
                    }
                }));
        dialog.show();
    }

    // --- watch episode picker ----------------------------------------------

    /** One row in the picker: a podcast header (toggles its group) or an episode
     *  (title + estimated on-watch size/duration). */
    private static class WatchRow {
        final boolean header;
        final String title;
        final String subtitle;   // episode: "~26 MB · 41 min"; header: null
        final String url;        // episode only
        final int groupStart;    // header only: index of first episode row
        int groupEnd;            // header only: exclusive end
        boolean checked;         // episode only

        WatchRow(boolean header, String title, String subtitle, String url, int groupStart) {
            this.header = header;
            this.title = title;
            this.subtitle = subtitle;
            this.url = url;
            this.groupStart = groupStart;
        }
    }

    /** Estimated rendered size/length of an episode on the watch: the file is
     *  trimmed (skip segments removed) and sped to the feed's synced rate, at
     *  ~128 kbps mp3 (16 KB/s). Mirrors the backend render inputs. */
    private static long estimatedRenderedSeconds(Context context, FeedItem item) {
        long durationSec = item.getMedia().getDuration() / 1000L;
        double skippedSec = 0;
        List<TrimClient.Segment> segments =
                TrimSegmentCache.get(context, item.getItemIdentifier());
        if (segments != null) {
            for (TrimClient.Segment s : segments) {
                if (s != null && s.end > s.start) {
                    skippedSec += s.end - s.start;
                }
            }
        }
        double speed = 1.0;
        if (item.getFeed() != null && item.getFeed().getPreferences() != null) {
            float feedSpeed = item.getFeed().getPreferences().getFeedPlaybackSpeed();
            if (feedSpeed != FeedPreferences.SPEED_USE_GLOBAL) {
                speed = feedSpeed;
            }
        }
        return Math.max(0, (long) ((durationSec - skippedSec) / speed));
    }

    private static String formatListeningTime(long renderedSec) {
        long h = renderedSec / 3600;
        long min = (renderedSec % 3600) / 60;
        return (h > 0) ? h + " h " + min + " min" : min + " min";
    }

    private static String formatOnWatch(long renderedSec) {
        long mb = Math.max(1, renderedSec * 16_000 / 1_000_000);
        return "~" + mb + " MB · " + formatListeningTime(renderedSec);
    }

    /** Pick which queued episodes sync to the watch: grouped by podcast (header
     *  toggles the group), each episode showing its estimated on-watch size and
     *  listening time, with All/None bulk actions and a live size total.
     *  Checked state loads from the account (empty selection = everything
     *  syncs); saving PUTs the full checked set. All-checked (or none) clears
     *  the filter so future queue adds keep syncing. */
    private static void showWatchEpisodes(Context context) {
        String token = UserPreferences.getTrimAccountToken();
        if (token == null) {
            return;
        }
        String bearer = "Bearer " + token;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<FeedItem> queue = DBReader.getQueue();
            Set<String> selected = new HashSet<>();
            try {
                retrofit2.Response<TrimClient.WatchSelection> resp =
                        TrimClient.getInstance().getWatchSelection(bearer).execute();
                if (resp.isSuccessful() && resp.body() != null
                        && resp.body().episode_urls != null) {
                    selected.addAll(resp.body().episode_urls);
                }
            } catch (Exception e) {
                Log.w(TAG, "watch selection load failed: " + e.getMessage());
            }

            // Group by podcast, preserving queue order of first appearance.
            Map<String, List<FeedItem>> byPodcast = new LinkedHashMap<>();
            for (FeedItem item : queue) {
                if (item.getMedia() == null || item.getMedia().getDownloadUrl() == null) {
                    continue;
                }
                String podcast = (item.getFeed() != null && item.getFeed().getTitle() != null)
                        ? item.getFeed().getTitle() : "Podcast";
                byPodcast.computeIfAbsent(podcast, k -> new ArrayList<>()).add(item);
            }

            List<WatchRow> rows = new ArrayList<>();
            List<long[]> renderedSecByRow = new ArrayList<>(); // parallel: [renderedSec]
            for (Map.Entry<String, List<FeedItem>> group : byPodcast.entrySet()) {
                WatchRow header = new WatchRow(true, group.getKey(), null, null, rows.size() + 1);
                rows.add(header);
                renderedSecByRow.add(new long[] {0});
                for (FeedItem item : group.getValue()) {
                    long renderedSec = estimatedRenderedSeconds(context, item);
                    WatchRow row = new WatchRow(false,
                            item.getTitle() == null ? "Episode" : item.getTitle(),
                            formatOnWatch(renderedSec),
                            item.getMedia().getDownloadUrl(), -1);
                    row.checked = selected.isEmpty() || selected.contains(row.url);
                    rows.add(row);
                    renderedSecByRow.add(new long[] {renderedSec});
                }
                header.groupEnd = rows.size();
            }

            main.post(() -> {
                if (rows.isEmpty()) {
                    Toast.makeText(context, R.string.trim_account_watch_episodes_empty,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showWatchEpisodesDialog(context, bearer, rows, renderedSecByRow);
            });
        }, "trim-watch-selection").start();
    }

    private static void showWatchEpisodesDialog(Context context, String bearer,
                                                List<WatchRow> rows,
                                                List<long[]> renderedSecByRow) {
        float dp = context.getResources().getDisplayMetrics().density;

        TextView summary = new TextView(context);
        summary.setPadding((int) (24 * dp), (int) (4 * dp), (int) (24 * dp), (int) (4 * dp));

        LinearLayout bulkBar = new LinearLayout(context);
        bulkBar.setOrientation(LinearLayout.HORIZONTAL);
        bulkBar.setPadding((int) (16 * dp), 0, (int) (16 * dp), 0);
        MaterialButton allBtn = new MaterialButton(context, null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        allBtn.setText(R.string.trim_account_watch_episodes_select_all);
        MaterialButton noneBtn = new MaterialButton(context, null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        noneBtn.setText(R.string.trim_account_watch_episodes_select_none);
        bulkBar.addView(allBtn);
        bulkBar.addView(noneBtn);

        ListView list = new ListView(context);
        list.setDivider(null);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(bulkBar);
        container.addView(summary);
        container.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Runnable updateSummary = () -> {
            int total = 0;
            int picked = 0;
            long totalSec = 0;
            for (int i = 0; i < rows.size(); i++) {
                WatchRow r = rows.get(i);
                if (r.header) {
                    continue;
                }
                total++;
                if (r.checked) {
                    picked++;
                    totalSec += renderedSecByRow.get(i)[0];
                }
            }
            summary.setText(context.getString(
                    R.string.trim_account_watch_episodes_summary,
                    picked, total, Math.max(1, totalSec * 16_000 / 1_000_000),
                    formatListeningTime(totalSec)));
        };

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return rows.size();
            }

            @Override
            public Object getItem(int position) {
                return rows.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public int getViewTypeCount() {
                return 2;
            }

            @Override
            public int getItemViewType(int position) {
                return rows.get(position).header ? 0 : 1;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                WatchRow row = rows.get(position);
                LinearLayout line;
                CheckBox box;
                TextView title;
                TextView subtitle = null;
                if (convertView instanceof LinearLayout) {
                    line = (LinearLayout) convertView;
                    box = (CheckBox) line.getChildAt(0);
                    LinearLayout textCol = (LinearLayout) line.getChildAt(1);
                    title = (TextView) textCol.getChildAt(0);
                    if (textCol.getChildCount() > 1) {
                        subtitle = (TextView) textCol.getChildAt(1);
                    }
                } else {
                    line = new LinearLayout(context);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    line.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    int lead = row.header ? (int) (12 * dp) : (int) (28 * dp);
                    line.setPadding(lead, (int) (6 * dp), (int) (16 * dp), (int) (6 * dp));
                    box = new CheckBox(context);
                    line.addView(box);
                    LinearLayout textCol = new LinearLayout(context);
                    textCol.setOrientation(LinearLayout.VERTICAL);
                    title = new TextView(context);
                    textCol.addView(title);
                    if (row.header) {
                        title.setTypeface(null, android.graphics.Typeface.BOLD);
                    } else {
                        subtitle = new TextView(context);
                        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                        subtitle.setAlpha(0.6f);
                        textCol.addView(subtitle);
                    }
                    line.addView(textCol);
                }

                title.setText(row.title);
                if (!row.header && subtitle != null) {
                    subtitle.setText(row.subtitle);
                }
                box.setOnCheckedChangeListener(null);
                if (row.header) {
                    boolean allChecked = true;
                    for (int i = row.groupStart; i < row.groupEnd; i++) {
                        if (!rows.get(i).checked) {
                            allChecked = false;
                            break;
                        }
                    }
                    box.setChecked(allChecked);
                    box.setOnCheckedChangeListener((b, isChecked) -> {
                        for (int i = row.groupStart; i < row.groupEnd; i++) {
                            rows.get(i).checked = isChecked;
                        }
                        notifyDataSetChanged();
                        updateSummary.run();
                    });
                } else {
                    box.setChecked(row.checked);
                    box.setOnCheckedChangeListener((b, isChecked) -> {
                        row.checked = isChecked;
                        notifyDataSetChanged(); // refresh the group header state
                        updateSummary.run();
                    });
                }
                View.OnClickListener rowClick = v -> box.toggle();
                line.setOnClickListener(rowClick);
                title.setOnClickListener(rowClick);
                return line;
            }
        };
        list.setAdapter(adapter);

        allBtn.setOnClickListener(v -> {
            for (WatchRow r : rows) {
                if (!r.header) {
                    r.checked = true;
                }
            }
            adapter.notifyDataSetChanged();
            updateSummary.run();
        });
        noneBtn.setOnClickListener(v -> {
            for (WatchRow r : rows) {
                if (!r.header) {
                    r.checked = false;
                }
            }
            adapter.notifyDataSetChanged();
            updateSummary.run();
        });
        updateSummary.run();

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.trim_account_watch_episodes)
                .setView(container)
                .setPositiveButton(R.string.trim_account_watch_episodes_save,
                        (d, w) -> saveWatchEpisodes(context, bearer, rows))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void saveWatchEpisodes(Context context, String bearer, List<WatchRow> rows) {
        List<String> picked = new ArrayList<>();
        int total = 0;
        for (WatchRow r : rows) {
            if (r.header) {
                continue;
            }
            total++;
            if (r.checked) {
                picked.add(r.url);
            }
        }
        // All checked (or none) -> clear the filter so the whole queue syncs,
        // including episodes queued later.
        List<String> toSend = (picked.size() == total || picked.isEmpty())
                ? new ArrayList<>() : picked;
        int pickedCount = picked.size();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String msg;
            try {
                retrofit2.Response<TrimClient.WatchSelection> resp =
                        TrimClient.getInstance().putWatchSelection(bearer, toSend).execute();
                msg = resp.isSuccessful()
                        ? context.getString(toSend.isEmpty()
                                ? R.string.trim_account_watch_episodes_all
                                : R.string.trim_account_watch_episodes_saved, pickedCount)
                        : context.getString(R.string.trim_account_watch_episodes_failed);
            } catch (Exception e) {
                Log.w(TAG, "watch selection save failed: " + e.getMessage());
                msg = context.getString(R.string.trim_account_watch_episodes_failed);
            }
            String finalMsg = msg;
            main.post(() -> Toast.makeText(context, finalMsg, Toast.LENGTH_LONG).show());
        }, "trim-watch-selection-save").start();
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
