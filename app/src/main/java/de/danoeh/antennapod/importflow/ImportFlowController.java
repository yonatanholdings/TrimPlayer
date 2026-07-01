package de.danoeh.antennapod.importflow;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.activity.OpmlImportActivity;
import de.danoeh.antennapod.event.AnalyticsEvent;
import de.danoeh.antennapod.portcast.ConflictDialog;
import de.danoeh.antennapod.portcast.ConflictRow;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.importexport.AntennaPodDbToPortcast;
import de.danoeh.antennapod.storage.importexport.DatabaseExporter;
import de.danoeh.antennapod.storage.importexport.PodcastAddictImporter;
import de.danoeh.antennapod.storage.importexport.PortcastImporter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Detects the type of a picked backup file and drives the matching additive
 * import (or, for a {@code .db}, the library-aware restore/merge flow). Extracted
 * from {@code ImportExportPreferencesFragment} so the first-run onboarding screen
 * and the Settings screen run the <em>identical</em> path — there's one place that
 * decides "what is this file and how do we import it."
 *
 * <p>The host (Activity/Fragment) owns the file-picker launcher and hands the
 * picked {@link Uri} to {@link #route(Uri)}. This controller owns the progress
 * dialog, conflict dialogs, background-worker hand-off, and import analytics.
 *
 * @see #ImportFlowController(AppCompatActivity, ImportHost, boolean)
 */
public class ImportFlowController {

    /** Lets a host (onboarding) react when an import is handed off, so it can show a
     *  success moment instead of the silent Snackbar Settings uses. {@code null} host
     *  ⇒ Settings behaviour (Snackbar, stay on screen). */
    public interface ImportHost {
        /** An in-place import was just enqueued (Podcast Addict / PortCast / AntennaPod
         *  merge). {@code broughtHistory} is false only for subscriptions-only sources. */
        void onImportEnqueued(String source, int subscriptionsCount, boolean broughtHistory);

        /** The import was handed off to another screen (OPML picker); just dismiss. */
        void onImportHandedOff();
    }

    private final AppCompatActivity activity;
    /** Reacts to import hand-off so onboarding can show a success screen; {@code null}
     *  for Settings (Snackbar + stay). Never invoked on error or unknown-type. */
    @Nullable private final ImportHost host;
    /** When true, a {@code .db} dropped onto an empty library is <em>merged</em>
     *  (re-subscribe online, immediate refresh, no app restart) rather than
     *  full-restored. Set by first-run onboarding: a brand-new user wants a clean,
     *  current library with cover art that loads, not a byte-exact offline restore
     *  that swaps the DB file and forces a process restart. Settings keeps the
     *  full-restore default so disaster recovery (reinstall → restore backup) can
     *  still bring back episodes the feeds no longer publish. */
    private final boolean preferMergeForFreshLibrary;
    private final ProgressDialog progressDialog;
    private Disposable disposable;

    public ImportFlowController(AppCompatActivity activity, @Nullable ImportHost host,
                                boolean preferMergeForFreshLibrary) {
        this.activity = activity;
        this.host = host;
        this.preferMergeForFreshLibrary = preferMergeForFreshLibrary;
        this.progressDialog = new ProgressDialog(activity);
        this.progressDialog.setIndeterminate(true);
        this.progressDialog.setMessage(activity.getString(R.string.please_wait));
    }

    /** Dispose any in-flight import work. Call from the host's onStop. */
    public void dispose() {
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private View snackbarAnchor() {
        return activity.findViewById(android.R.id.content);
    }

    private enum ImportType { DATABASE, OPML, PODCAST_ADDICT, PORTCAST, UNKNOWN }

    /** Analytics import_source label for a detected file type; null = don't track. */
    private static String importSourceFor(ImportType type) {
        switch (type) {
            case DATABASE: return "database";
            case OPML: return "opml";
            case PODCAST_ADDICT: return "podcast_addict";
            case PORTCAST: return "portcast";
            default: return null;
        }
    }

    /** Detect the picked file's type and route it to the matching importer. */
    public void route(Uri uri) {
        if (uri == null) {
            return;
        }
        progressDialog.show();
        disposable = Single.fromCallable(() -> detectImportType(uri))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(type -> {
                    progressDialog.dismiss();
                    String startedSource = importSourceFor(type);
                    if (startedSource != null) {
                        EventBus.getDefault().post(AnalyticsEvent.importStarted(startedSource));
                    }
                    switch (type) {
                        case DATABASE:
                            routeDatabaseImport(uri);
                            break;
                        case OPML:
                            // import_completed for OPML fires in OpmlImportActivity once
                            // the user picks feeds and confirms.
                            Intent intent = new Intent(activity, OpmlImportActivity.class);
                            intent.setData(uri);
                            activity.startActivity(intent);
                            if (host != null) {
                                host.onImportHandedOff();
                            }
                            break;
                        case PODCAST_ADDICT:
                            importFromPodcastAddict(uri);
                            break;
                        case PORTCAST:
                            importFromPortcast(uri);
                            break;
                        default:
                            showUnknownTypeDialog();
                    }
                }, error -> {
                    progressDialog.dismiss();
                    showErrorDialog(error);
                });
    }

    private void showUnknownTypeDialog() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.export_error_label)
                .setMessage(R.string.import_unknown_type)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private ImportType detectImportType(Uri uri) throws java.io.IOException {
        try (java.io.InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                return ImportType.UNKNOWN;
            }
            byte[] header = new byte[200];
            int bytesRead = stream.read(header);
            if (bytesRead < 4) {
                return ImportType.UNKNOWN;
            }
            // ZIP magic PK\x03\x04 → Podcast Addict backup
            if ((header[0] & 0xFF) == 0x50 && (header[1] & 0xFF) == 0x4B
                    && (header[2] & 0xFF) == 0x03 && (header[3] & 0xFF) == 0x04) {
                return ImportType.PODCAST_ADDICT;
            }
            // SQLite magic → TrimPlayer database backup
            if (bytesRead >= 16) {
                String magic = new String(header, 0, 16, "US-ASCII");
                if (magic.startsWith("SQLite format 3")) {
                    return ImportType.DATABASE;
                }
            }
            // XML/OPML text — skip UTF-8 BOM if present
            int textStart = 0;
            if (bytesRead >= 3 && (header[0] & 0xFF) == 0xEF
                    && (header[1] & 0xFF) == 0xBB && (header[2] & 0xFF) == 0xBF) {
                textStart = 3;
            }
            String text = new String(header, textStart,
                    Math.min(bytesRead - textStart, header.length - textStart), "UTF-8").trim();
            if (text.startsWith("<?xml") || text.toLowerCase().startsWith("<opml")) {
                return ImportType.OPML;
            }
            // PortCast: JSON object whose first top-level key is "portcast".
            // We only read a 200-byte head, so look for the literal token.
            if (text.startsWith("{") && text.contains("\"portcast\"")) {
                return ImportType.PORTCAST;
            }
            return ImportType.UNKNOWN;
        }
    }

    /**
     * Pick how a picked {@code .db} backup is applied. The two engines aren't
     * interchangeable: a <em>full restore</em> swaps the whole database file —
     * offline, byte-exact, and the only path that brings back episodes the feeds
     * no longer publish — but it wipes the current library. A <em>merge</em>
     * re-subscribes the backup's feeds online and folds its state on top without
     * deleting anything. So we route by what's at stake:
     *
     * <ul>
     *   <li><b>Empty library</b> → full restore by default (safe — nothing to wipe —
     *       and strictly more complete). Exception: onboarding sets
     *       {@code preferMergeForFreshLibrary} so a new user merges instead, getting
     *       an online re-subscribe + refresh (cover art loads) and no app restart.</li>
     *   <li><b>Existing library</b> → ask. Default to merge (the non-destructive
     *       choice, now safe to tap through thanks to furthest-progress conflict
     *       defaults and feed-URL dedupe), with full restore as an explicit,
     *       separately-confirmed escape hatch for disaster recovery.</li>
     * </ul>
     */
    private void routeDatabaseImport(Uri uri) {
        progressDialog.show();
        disposable = Single
                .fromCallable(() -> !DBReader.getFeedList().isEmpty())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(hasExistingLibrary -> {
                    progressDialog.dismiss();
                    if (hasExistingLibrary) {
                        chooseDatabaseApplyMode(uri);
                    } else if (preferMergeForFreshLibrary) {
                        // Onboarding: merge into the empty library — re-subscribes
                        // online and refreshes (cover art loads), no app restart.
                        mergeDatabaseFromUri(uri);
                    } else {
                        confirmDatabaseImport(uri);
                    }
                }, error -> {
                    progressDialog.dismiss();
                    showErrorDialog(error);
                });
    }

    private void chooseDatabaseApplyMode(Uri uri) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.database_apply_choice_title)
                .setMessage(R.string.database_apply_choice_message)
                .setPositiveButton(R.string.database_apply_merge, (dialog, which) -> mergeDatabaseFromUri(uri))
                .setNeutralButton(R.string.database_apply_full_restore,
                        (dialog, which) -> confirmDatabaseImport(uri))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDatabaseImport(Uri uri) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.database_import_label)
                .setMessage(R.string.database_import_warning)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> restoreDatabaseFromUri(uri))
                .show();
    }

    private void restoreDatabaseFromUri(Uri uri) {
        progressDialog.show();
        disposable = Completable.fromAction(() -> DatabaseExporter.importBackup(uri, activity))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    EventBus.getDefault().post(AnalyticsEvent.importCompleted("database", -1));
                    // A full restore swaps the whole DB file: the restored feeds have
                    // no downloaded cover art and the restart loses any refresh state.
                    // Flag a one-shot refresh so MainActivity fetches covers right after
                    // the restart, instead of leaving blank "white tiles" until the
                    // hourly periodic job eventually runs.
                    activity.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean(MainActivity.PREF_PENDING_RESTORE_REFRESH, true).apply();
                    showDatabaseImportSuccessDialog();
                    progressDialog.dismiss();
                }, this::showErrorDialog);
    }

    /**
     * Translate the picked AntennaPod/TrimPlayer .db backup into a PortCast
     * document and feed it through the existing additive PortCast import flow
     * (conflict review + background subscribe), so the backup is merged on top
     * of the current library rather than replacing it. Reached from the
     * merge-vs-restore chooser ({@link #chooseDatabaseApplyMode}).
     */
    private void mergeDatabaseFromUri(Uri uri) {
        progressDialog.show();
        disposable = Observable.fromCallable(() -> {
            String json = AntennaPodDbToPortcast.toPortcastJson(
                    activity, uri, BuildConfig.VERSION_NAME);
            try (java.io.InputStream stream =
                         new java.io.ByteArrayInputStream(json.getBytes("UTF-8"))) {
                return PortcastImporter.previewImport(activity, stream);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(preview -> {
                    progressDialog.dismiss();
                    // Completion attributes to "database": the source the user
                    // picked was a .db backup, even though it rides the PortCast
                    // engine. Keeps the import funnel honest vs. real PortCast files.
                    if (preview.conflicts.isEmpty()) {
                        executePortcastImport(preview, "database");
                    } else {
                        showPortcastConflictDialog(preview, "database");
                    }
                }, error -> {
                    progressDialog.dismiss();
                    String msg = activity.getString(R.string.portcast_import_error, error.getMessage());
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.database_merge_label)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
    }

    private void showDatabaseImportSuccessDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle(R.string.successful_import_label);
        builder.setMessage(R.string.import_ok);
        builder.setCancelable(false);
        builder.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> forceRestart());
        builder.show();
    }

    private void importFromPodcastAddict(Uri uri) {
        if (uri == null) {
            return;
        }
        progressDialog.show();
        disposable = Observable.fromCallable(() -> {
            try (java.io.InputStream stream = activity.getContentResolver().openInputStream(uri)) {
                return PodcastAddictImporter.previewImport(activity, stream);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(preview -> {
                    progressDialog.dismiss();
                    if (preview.conflicts.isEmpty()) {
                        executeImport(preview);
                    } else {
                        showPodcastAddictConflictDialog(preview);
                    }
                }, error -> {
                    progressDialog.dismiss();
                    String msg = activity.getString(R.string.podcast_addict_import_error, error.getMessage());
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.podcast_addict_import_title)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
    }

    private void importFromPortcast(Uri uri) {
        if (uri == null) {
            return;
        }
        progressDialog.show();
        disposable = Observable.fromCallable(() -> {
            try (java.io.InputStream stream = activity.getContentResolver().openInputStream(uri)) {
                return PortcastImporter.previewImport(activity, stream);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(preview -> {
                    progressDialog.dismiss();
                    if (preview.conflicts.isEmpty()) {
                        executePortcastImport(preview, "portcast");
                    } else {
                        showPortcastConflictDialog(preview, "portcast");
                    }
                }, error -> {
                    progressDialog.dismiss();
                    String msg = activity.getString(R.string.portcast_import_error, error.getMessage());
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.portcast_import_title)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
    }

    private void showPodcastAddictConflictDialog(PodcastAddictImporter.ImportPreview preview) {
        List<ConflictRow> rows = new ArrayList<>();
        for (PodcastAddictImporter.ConflictEpisode c : preview.conflicts) {
            ConflictRow row = new ConflictRow();
            row.episodeTitle = c.episodeTitle;
            row.feedTitle = c.feedTitle;
            row.apStateDescription = c.apStateDescription;
            row.incomingStateDescription = describePaState(c.paState);
            row.lastPlayedMs = c.paState.playbackDateMs;
            row.useIncoming = c.usePodcastAddict;
            rows.add(row);
        }
        ConflictDialog.show(activity, rows, "Podcast Addict",
                activity.getString(R.string.podcast_addict_conflicts_title, preview.conflicts.size()),
                () -> {
                    for (int i = 0; i < rows.size(); i++) {
                        preview.conflicts.get(i).usePodcastAddict = rows.get(i).useIncoming;
                    }
                    executeImport(preview);
                });
    }

    private void showPortcastConflictDialog(PortcastImporter.ImportPreview preview, String source) {
        List<ConflictRow> rows = new ArrayList<>();
        for (PortcastImporter.ConflictEpisode c : preview.conflicts) {
            ConflictRow row = new ConflictRow();
            row.episodeTitle = c.episodeTitle;
            row.feedTitle = c.feedTitle;
            row.apStateDescription = c.apStateDescription;
            row.incomingStateDescription = describePortcastState(c.incomingState);
            row.lastPlayedMs = c.incomingState.lastPlayedMs;
            row.useIncoming = c.useIncoming;
            rows.add(row);
        }
        ConflictDialog.show(activity, rows, "PortCast",
                activity.getString(R.string.portcast_import_conflicts_title, preview.conflicts.size()),
                () -> {
                    for (int i = 0; i < rows.size(); i++) {
                        preview.conflicts.get(i).useIncoming = rows.get(i).useIncoming;
                    }
                    executePortcastImport(preview, source);
                });
    }

    private String describePaState(PodcastAddictImporter.EpisodeState state) {
        if (state.played) {
            return activity.getString(R.string.podcast_addict_state_played);
        }
        if (state.positionMs > 0) {
            int s = state.positionMs / 1000;
            return activity.getString(R.string.podcast_addict_state_inprogress,
                    String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60));
        }
        return activity.getString(R.string.podcast_addict_state_favorite);
    }

    private String describePortcastState(PortcastImporter.EpisodeState state) {
        // Spec status enum: unplayed | in_progress | completed | archived.
        // "completed" and "archived" both render as Played here (closest user analogue).
        if ("completed".equals(state.status) || "archived".equals(state.status)) {
            return activity.getString(R.string.podcast_addict_state_played);
        }
        if ("in_progress".equals(state.status) && state.positionMs > 0) {
            int s = state.positionMs / 1000;
            return activity.getString(R.string.podcast_addict_state_inprogress,
                    String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60));
        }
        return activity.getString(R.string.podcast_addict_state_favorite);
    }

    private void executeImport(PodcastAddictImporter.ImportPreview preview) {
        progressDialog.show();
        // executeImport is a quick stash + enqueue of a background worker that does
        // the per-feed subscribe work with a progress notification, so the dialog
        // dismisses after the stash instead of waiting on the whole subscribe loop.
        disposable = Completable
                .fromAction(() -> PodcastAddictImporter.executeImport(activity, preview))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    progressDialog.dismiss();
                    EventBus.getDefault().post(
                            AnalyticsEvent.importCompleted("podcast_addict", preview.feeds.size()));
                    if (host != null) {
                        host.onImportEnqueued("podcast_addict", preview.feeds.size(), true);
                    } else {
                        Snackbar.make(snackbarAnchor(),
                                activity.getString(R.string.podcast_addict_import_started, preview.feeds.size()),
                                Snackbar.LENGTH_LONG).show();
                    }
                }, error -> {
                    progressDialog.dismiss();
                    String msg = activity.getString(R.string.podcast_addict_import_error, error.getMessage());
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.podcast_addict_import_title)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
    }

    private void executePortcastImport(PortcastImporter.ImportPreview preview, String source) {
        progressDialog.show();
        disposable = Completable
                .fromAction(() -> PortcastImporter.executeImport(activity, preview))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    progressDialog.dismiss();
                    EventBus.getDefault().post(
                            AnalyticsEvent.importCompleted(source, preview.feeds.size()));
                    if (host != null) {
                        host.onImportEnqueued(source, preview.feeds.size(), !"opml".equals(source));
                    } else {
                        int unresolved = preview.unresolvableFeeds.size();
                        String msg = unresolved > 0
                                ? activity.getString(R.string.portcast_import_started_with_unresolved,
                                        preview.feeds.size(), unresolved)
                                : activity.getString(R.string.portcast_import_started, preview.feeds.size());
                        Snackbar.make(snackbarAnchor(), msg, Snackbar.LENGTH_LONG).show();
                    }
                }, error -> {
                    progressDialog.dismiss();
                    String msg = activity.getString(R.string.portcast_import_error, error.getMessage());
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.portcast_import_title)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
    }

    private void showErrorDialog(final Throwable error) {
        progressDialog.dismiss();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.export_error_label)
                .setMessage(error.getMessage())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void forceRestart() {
        PackageManager pm = activity.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(activity.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.getApplicationContext().startActivity(intent);
        Runtime.getRuntime().exit(0);
    }
}
