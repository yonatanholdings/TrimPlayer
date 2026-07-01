package de.danoeh.antennapod.portcast;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.AnalyticsEvent;
import de.danoeh.antennapod.storage.importexport.PortcastImporter;
import de.danoeh.antennapod.ui.common.ThemeSwitcher;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;

/**
 * Tap-to-import target for {@code .portcast.json} files arriving via
 * VIEW (Gmail attachment, file manager, Drive) or SEND (Gmail share-sheet).
 * Registered in the manifest with the intent-filters from
 * {@code trimplayer-android-migration-brief.md} §1.2.1.
 *
 * <p>For M3 this is a fast-path: parse, resolve, show a one-screen
 * confirmation, execute. Each conflict defaults to whichever side has more
 * listening progress (see {@link PortcastImporter#preferIncomingByProgress}),
 * so tapping straight through never silently rewinds or un-completes an
 * episode; conflicts are surfaced as a count + pointer to the full
 * manual-review flow in Settings → Import/Export. A full inline conflict UI
 * port from {@code ImportExportPreferencesFragment.showConflictDialog} is a
 * follow-up.
 */
public class PortcastImportActivity extends AppCompatActivity {

    private static final String TAG = "PortcastImport";

    /**
     * Origin of the .portcast.json being imported, for analytics attribution.
     * SpotifyMigrationActivity sets this to "spotify"; absent (the default
     * share-sheet / VIEW entry) means a hand-supplied PortCast file → "portcast".
     */
    public static final String EXTRA_IMPORT_SOURCE = "import_source";

    private String importSource = "portcast";

    private ProgressBar progress;
    private TextView statusView;
    private TextView summaryView;
    private TextView unresolvedWarningView;
    private TextView conflictsNoteView;
    private Button importButton;
    private Button cancelButton;

    @Nullable private Disposable disposable;
    @Nullable private PortcastImporter.ImportPreview pendingPreview;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(ThemeSwitcher.getNoTitleTheme(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.portcast_import_activity);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.portcast_import_activity_title);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        progress = findViewById(R.id.progress);
        statusView = findViewById(R.id.status);
        summaryView = findViewById(R.id.summary);
        unresolvedWarningView = findViewById(R.id.unresolvedWarning);
        conflictsNoteView = findViewById(R.id.conflictsNote);
        importButton = findViewById(R.id.importButton);
        cancelButton = findViewById(R.id.cancelButton);

        cancelButton.setOnClickListener(v -> finish());
        importButton.setOnClickListener(v -> executeImport());

        String src = getIntent().getStringExtra(EXTRA_IMPORT_SOURCE);
        if (src != null) {
            importSource = src;
        }

        Uri uri = extractUri(getIntent());
        if (uri == null) {
            showFatalError(getString(R.string.portcast_import_error,
                    "No file URI in intent."));
            return;
        }
        // import_started for the Spotify path is fired by SpotifyMigrationActivity
        // when the user enters that flow, so only fire it here for the direct
        // share-sheet / VIEW entry. Guard on savedInstanceState so a rotation
        // doesn't re-count.
        if (savedInstanceState == null && !"spotify".equals(importSource)) {
            EventBus.getDefault().post(AnalyticsEvent.importStarted(importSource));
        }
        startPreview(uri);
    }

    @Override
    protected void onDestroy() {
        if (disposable != null) {
            disposable.dispose();
            disposable = null;
        }
        super.onDestroy();
    }

    /**
     * VIEW intent → {@code data} URI; SEND intent → {@code EXTRA_STREAM}.
     */
    @Nullable
    private static Uri extractUri(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }
        Uri data = intent.getData();
        if (data != null) {
            return data;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        return null;
    }

    private void startPreview(Uri uri) {
        statusView.setText(R.string.portcast_import_parsing);
        progress.setVisibility(View.VISIBLE);

        disposable = Observable.fromCallable(() -> {
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) {
                    throw new IllegalStateException("ContentResolver returned a null stream.");
                }
                return PortcastImporter.previewImport(this, stream,
                        (resolved, total) -> runOnUiThread(() ->
                                statusView.setText(getString(
                                        R.string.portcast_import_resolving, resolved, total))));
            }
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(this::onPreviewReady, this::onPreviewError);
    }

    private void onPreviewReady(PortcastImporter.ImportPreview preview) {
        pendingPreview = preview;
        progress.setVisibility(View.GONE);
        statusView.setVisibility(View.GONE);

        int episodesCount = preview.nonConflictingStates.size() + preview.conflicts.size();
        summaryView.setVisibility(View.VISIBLE);
        summaryView.setText(getString(R.string.portcast_import_confirmation,
                preview.feeds.size(), episodesCount));

        if (!preview.unresolvableFeeds.isEmpty()) {
            unresolvedWarningView.setVisibility(View.VISIBLE);
            unresolvedWarningView.setText(getString(
                    R.string.portcast_import_unresolved_warning,
                    preview.unresolvableFeeds.size()));
        }
        if (!preview.conflicts.isEmpty()) {
            conflictsNoteView.setVisibility(View.VISIBLE);
            conflictsNoteView.setText(getString(
                    R.string.portcast_import_conflicts_note, preview.conflicts.size()));
        }
        importButton.setEnabled(true);
    }

    private void onPreviewError(Throwable error) {
        Log.e(TAG, "preview failed", error);
        showFatalError(getString(R.string.portcast_import_error,
                error.getMessage() != null ? error.getMessage() : error.toString()));
    }

    private void executeImport() {
        if (pendingPreview == null) {
            return;
        }
        PortcastImporter.ImportPreview preview = pendingPreview;

        // If the import touches episodes with local play data, route through
        // the shared conflict dialog so the user can choose per row instead
        // of silently accepting "use incoming."
        if (!preview.conflicts.isEmpty()) {
            showConflictDialog(preview);
            return;
        }
        runImport(preview);
    }

    private void showConflictDialog(PortcastImporter.ImportPreview preview) {
        List<ConflictRow> rows = new ArrayList<>(preview.conflicts.size());
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
        ConflictDialog.show(this, rows, "PortCast",
                getString(R.string.portcast_import_conflicts_title, preview.conflicts.size()),
                () -> {
                    for (int i = 0; i < rows.size(); i++) {
                        preview.conflicts.get(i).useIncoming = rows.get(i).useIncoming;
                    }
                    runImport(preview);
                });
    }

    private static String describePortcastState(PortcastImporter.EpisodeState state) {
        // Spec status enum: unplayed | in_progress | completed | archived.
        // "completed" and "archived" both render as Played (closest user analogue).
        if ("completed".equals(state.status) || "archived".equals(state.status)) {
            return "Played";
        }
        if ("in_progress".equals(state.status) && state.positionMs > 0) {
            int s = state.positionMs / 1000;
            return String.format(java.util.Locale.US,
                    "In progress at %d:%02d", s / 60, s % 60);
        }
        return "Unplayed";
    }

    private void runImport(PortcastImporter.ImportPreview preview) {
        importButton.setEnabled(false);
        cancelButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(R.string.portcast_import_parsing);

        disposable = Completable.fromAction(() -> PortcastImporter.executeImport(this, preview))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    EventBus.getDefault().post(
                            AnalyticsEvent.importCompleted(importSource, preview.feeds.size()));
                    int unresolved = preview.unresolvableFeeds.size();
                    String msg = unresolved > 0
                            ? getString(R.string.portcast_import_started_with_unresolved,
                                    preview.feeds.size(), unresolved)
                            : getString(R.string.portcast_import_started, preview.feeds.size());
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    finish();
                }, error -> {
                    Log.e(TAG, "execute failed", error);
                    showFatalError(getString(R.string.portcast_import_error,
                            error.getMessage() != null ? error.getMessage() : error.toString()));
                });
    }

    private void showFatalError(String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.portcast_import_title)
                .setMessage(message)
                .setOnDismissListener(d -> finish())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
