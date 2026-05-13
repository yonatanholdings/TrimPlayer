package de.danoeh.antennapod.ui.screen.preferences;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.contract.ActivityResultContracts.GetContent;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;
import com.google.android.material.snackbar.Snackbar;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.OpmlImportActivity;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.importexport.AutomaticDatabaseExportWorker;
import de.danoeh.antennapod.storage.importexport.DatabaseExporter;
import de.danoeh.antennapod.storage.importexport.FavoritesWriter;
import de.danoeh.antennapod.storage.importexport.HtmlWriter;
import de.danoeh.antennapod.storage.importexport.OpmlWriter;
import de.danoeh.antennapod.storage.importexport.PodcastAddictImporter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ImportExportPreferencesFragment extends AnimatedPreferenceFragment {
    private static final String TAG = "ImportExPrefFragment";
    public static final String ARG_IMPORT_URI = "ImportUri";
    private static final String PREF_OPML_EXPORT = "prefOpmlExport";
    private static final String PREF_HTML_EXPORT = "prefHtmlExport";
    private static final String PREF_DATABASE_EXPORT = "prefDatabaseExport";
    private static final String PREF_AUTOMATIC_DATABASE_EXPORT = "prefAutomaticDatabaseExport";
    private static final String PREF_FAVORITE_EXPORT = "prefFavoritesExport";
    private static final String PREF_IMPORT = "prefImport";
    private static final String DEFAULT_OPML_OUTPUT_NAME = "trimplayer-feeds-%s.opml";
    private static final String CONTENT_TYPE_OPML = "text/x-opml";
    private static final String DEFAULT_HTML_OUTPUT_NAME = "trimplayer-feeds-%s.html";
    private static final String CONTENT_TYPE_HTML = "text/html";
    private static final String DEFAULT_FAVORITES_OUTPUT_NAME = "trimplayer-favorites-%s.html";
    private static final String DATABASE_EXPORT_FILENAME = "TrimPlayerBackup-%s.db";

    private final ActivityResultLauncher<Intent> chooseOpmlExportPathLauncher =
            registerForActivityResult(new StartActivityForResult(),
                    result -> exportToDocument(result, Export.OPML));
    private final ActivityResultLauncher<Intent> chooseHtmlExportPathLauncher =
            registerForActivityResult(new StartActivityForResult(),
                    result -> exportToDocument(result, Export.HTML));
    private final ActivityResultLauncher<Intent> chooseFavoritesExportPathLauncher =
            registerForActivityResult(new StartActivityForResult(),
                    result -> exportToDocument(result, Export.FAVORITES));
    private final ActivityResultLauncher<String> backupDatabaseLauncher =
            registerForActivityResult(new BackupDatabase(), this::backupDatabaseResult);
    private final ActivityResultLauncher<String> unifiedImportLauncher =
            registerForActivityResult(new GetContent(), this::handleUnifiedImport);
    private final ActivityResultLauncher<Uri> automaticBackupLauncher =
            registerForActivityResult(new PickWritableFolder(), this::setupAutomaticBackup);

    private Disposable disposable;
    private ProgressDialog progressDialog;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_import_export);
        setupStorageScreen();
        progressDialog = new ProgressDialog(getContext());
        progressDialog.setIndeterminate(true);
        progressDialog.setMessage(getContext().getString(R.string.please_wait));

        if (savedInstanceState == null && getArguments() != null) {
            Uri pendingImport = getArguments().getParcelable(ARG_IMPORT_URI);
            if (pendingImport != null) {
                getArguments().remove(ARG_IMPORT_URI);
                handleUnifiedImport(pendingImport);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.import_export_pref);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private void setupStorageScreen() {
        findPreference(PREF_IMPORT).setOnPreferenceClickListener(preference -> {
            try {
                unifiedImportLauncher.launch("*/*");
            } catch (ActivityNotFoundException e) {
                Snackbar.make(getView(), R.string.unable_to_start_system_file_manager, Snackbar.LENGTH_LONG).show();
            }
            return true;
        });
        findPreference(PREF_OPML_EXPORT).setOnPreferenceClickListener(
                preference -> {
                    openExportPathPicker(Export.OPML, chooseOpmlExportPathLauncher);
                    return true;
                }
        );
        findPreference(PREF_HTML_EXPORT).setOnPreferenceClickListener(
                preference -> {
                    openExportPathPicker(Export.HTML, chooseHtmlExportPathLauncher);
                    return true;
                });
        findPreference(PREF_DATABASE_EXPORT).setOnPreferenceClickListener(
                preference -> {
                    try {
                        backupDatabaseLauncher.launch(dateStampFilename(DATABASE_EXPORT_FILENAME));
                    } catch (ActivityNotFoundException e) {
                        Snackbar.make(getView(), R.string.unable_to_start_system_file_manager, Snackbar.LENGTH_LONG)
                                .show();
                    }
                    return true;
                });
        ((SwitchPreferenceCompat) findPreference(PREF_AUTOMATIC_DATABASE_EXPORT))
                .setChecked(UserPreferences.getAutomaticExportFolder() != null);
        findPreference(PREF_AUTOMATIC_DATABASE_EXPORT).setOnPreferenceChangeListener(
                (preference, newValue) -> {
                    if (Boolean.TRUE.equals(newValue)) {
                        try {
                            automaticBackupLauncher.launch(null);
                        } catch (ActivityNotFoundException e) {
                            Snackbar.make(getView(), R.string.unable_to_start_system_file_manager, Snackbar.LENGTH_LONG)
                                    .show();
                        }
                        return false;
                    } else {
                        UserPreferences.setAutomaticExportFolder(null);
                        AutomaticDatabaseExportWorker.enqueueIfNeeded(getContext(), false);
                    }
                    return true;
                });
        findPreference(PREF_FAVORITE_EXPORT).setOnPreferenceClickListener(
                preference -> {
                    openExportPathPicker(Export.FAVORITES, chooseFavoritesExportPathLauncher);
                    return true;
                });
    }

    private String dateStampFilename(String fname) {
        return String.format(fname, new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
    }

    private enum ImportType { DATABASE, OPML, PODCAST_ADDICT, UNKNOWN }

    private void handleUnifiedImport(Uri uri) {
        if (uri == null) {
            return;
        }
        progressDialog.show();
        disposable = io.reactivex.rxjava3.core.Single.fromCallable(() -> detectImportType(uri))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(type -> {
                    progressDialog.dismiss();
                    switch (type) {
                        case DATABASE:
                            confirmDatabaseImport(uri);
                            break;
                        case OPML:
                            Intent intent = new Intent(getContext(), OpmlImportActivity.class);
                            intent.setData(uri);
                            startActivity(intent);
                            break;
                        case PODCAST_ADDICT:
                            importFromPodcastAddict(uri);
                            break;
                        default:
                            new MaterialAlertDialogBuilder(getContext())
                                    .setTitle(R.string.export_error_label)
                                    .setMessage(R.string.import_unknown_type)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                    }
                }, error -> {
                    progressDialog.dismiss();
                    showExportErrorDialog(error);
                });
    }

    private ImportType detectImportType(Uri uri) throws java.io.IOException {
        try (java.io.InputStream stream = getContext().getContentResolver().openInputStream(uri)) {
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
            String text = new String(header, textStart, Math.min(bytesRead - textStart, 100), "UTF-8").trim();
            if (text.startsWith("<?xml") || text.toLowerCase().startsWith("<opml")) {
                return ImportType.OPML;
            }
            return ImportType.UNKNOWN;
        }
    }

    private void confirmDatabaseImport(Uri uri) {
        new MaterialAlertDialogBuilder(getActivity())
                .setTitle(R.string.database_import_label)
                .setMessage(R.string.database_import_warning)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> restoreDatabaseFromUri(uri))
                .show();
    }

    private void restoreDatabaseFromUri(Uri uri) {
        progressDialog.show();
        disposable = Completable.fromAction(() -> DatabaseExporter.importBackup(uri, getContext()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    showDatabaseImportSuccessDialog();
                    progressDialog.dismiss();
                }, this::showExportErrorDialog);
    }

    private void showDatabaseImportSuccessDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext());
        builder.setTitle(R.string.successful_import_label);
        builder.setMessage(R.string.import_ok);
        builder.setCancelable(false);
        builder.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> forceRestart());
        builder.show();
    }

    void showExportSuccessSnackbar(Uri uri, String mimeType) {
        Snackbar.make(getView(), R.string.export_success_title, Snackbar.LENGTH_LONG)
                .setAction(R.string.share_label, v ->
                        new ShareCompat.IntentBuilder(getContext())
                                .setType(mimeType)
                                .addStream(uri)
                                .setChooserTitle(R.string.share_label)
                                .startChooser())
                .show();
    }

    private void showExportErrorDialog(final Throwable error) {
        progressDialog.dismiss();
        final MaterialAlertDialogBuilder alert = new MaterialAlertDialogBuilder(getContext());
        alert.setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss());
        alert.setTitle(R.string.export_error_label);
        alert.setMessage(error.getMessage());
        alert.show();
    }

    private void backupDatabaseResult(final Uri uri) {
        if (uri == null) {
            return;
        }
        progressDialog.show();
        disposable = Completable.fromAction(() -> DatabaseExporter.exportToDocument(uri, getContext()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    showExportSuccessSnackbar(uri, "application/x-sqlite3");
                    progressDialog.dismiss();
                }, this::showExportErrorDialog);
    }

    private void openExportPathPicker(Export exportType, ActivityResultLauncher<Intent> result) {
        String title = dateStampFilename(exportType.outputNameTemplate);

        Intent intentPickAction = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(exportType.contentType)
                .putExtra(Intent.EXTRA_TITLE, title);

        // Creates an implicit intent to launch a file manager which lets
        // the user choose a specific directory to export to.
        try {
            result.launch(intentPickAction);
            return;
        } catch (ActivityNotFoundException e) {
            Snackbar.make(getView(), R.string.unable_to_start_system_file_manager, Snackbar.LENGTH_LONG)
                    .show();
        }

        // If we are using a SDK lower than API 21 or the implicit intent failed
        // fallback to the legacy export process
        File output = new File(UserPreferences.getDataFolder("export/"), title);
        exportToFile(exportType, output);
    }

    private void exportToFile(Export exportType, File output) {
        progressDialog.show();
        disposable = Observable.create(
                subscriber -> {
                    if (output.exists()) {
                        boolean success = output.delete();
                        Log.w(TAG, "Overwriting previously exported file: " + success);
                    }
                    try (FileOutputStream fileOutputStream = new FileOutputStream(output)) {
                        writeToStream(fileOutputStream, exportType);
                        subscriber.onNext(output);
                    } catch (IOException e) {
                        subscriber.onError(e);
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(outputFile -> {
                    progressDialog.dismiss();
                    Uri fileUri = FileProvider.getUriForFile(getActivity().getApplicationContext(),
                            getString(R.string.provider_authority), output);
                    showExportSuccessSnackbar(fileUri, exportType.contentType);
                }, this::showExportErrorDialog, progressDialog::dismiss);
    }

    private void exportToDocument(final ActivityResult result, Export exportType) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }
        progressDialog.show();
        DocumentFile output = DocumentFile.fromSingleUri(getContext(), result.getData().getData());
        disposable = Observable.create(
                subscriber -> {
                    try (OutputStream outputStream = getContext().getContentResolver()
                            .openOutputStream(output.getUri(), "wt")) {
                        writeToStream(outputStream, exportType);
                        subscriber.onNext(output);
                    } catch (IOException e) {
                        subscriber.onError(e);
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignore -> {
                    progressDialog.dismiss();
                    showExportSuccessSnackbar(output.getUri(), exportType.contentType);
                }, this::showExportErrorDialog, progressDialog::dismiss);
    }

    private void writeToStream(OutputStream outputStream, Export type) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, Charset.forName("UTF-8"))) {
            switch (type) {
                case HTML:
                    HtmlWriter.writeDocument(DBReader.getFeedList(), writer, getContext());
                    break;
                case OPML:
                    OpmlWriter.writeDocument(DBReader.getFeedList(), writer);
                    break;
                case FAVORITES:
                    List<FeedItem> allFavorites = DBReader.getEpisodes(0, Integer.MAX_VALUE,
                            new FeedItemFilter(FeedItemFilter.IS_FAVORITE), SortOrder.DATE_NEW_OLD);
                    FavoritesWriter.writeDocument(allFavorites, writer, getContext());
                    break;
                default:
                    showExportErrorDialog(new Exception("Invalid export type"));
                    break;
            }
        }
    }

    private void setupAutomaticBackup(Uri uri) {
        if (uri == null) {
            return;
        }
        getActivity().getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        UserPreferences.setAutomaticExportFolder(uri.toString());
        AutomaticDatabaseExportWorker.enqueueIfNeeded(getContext(), true);
        ((SwitchPreferenceCompat) findPreference(PREF_AUTOMATIC_DATABASE_EXPORT)).setChecked(true);
    }

    private void importFromPodcastAddict(Uri uri) {
        if (uri == null) {
            return;
        }
        progressDialog.show();
        disposable = Observable.fromCallable(() -> {
            try (java.io.InputStream stream = getContext().getContentResolver().openInputStream(uri)) {
                return PodcastAddictImporter.previewImport(getContext(), stream);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(preview -> {
                    progressDialog.dismiss();
                    if (preview.conflicts.isEmpty()) {
                        executeImport(preview);
                    } else {
                        showConflictDialog(preview);
                    }
                }, error -> {
                    progressDialog.dismiss();
                    String msg = getString(R.string.podcast_addict_import_error, error.getMessage());
                    new MaterialAlertDialogBuilder(getContext())
                            .setTitle(R.string.podcast_addict_import_title)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
    }

    private void showConflictDialog(PodcastAddictImporter.ImportPreview preview) {
        View dialogView = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_podcast_addict_conflicts, null);

        TextView summaryView = dialogView.findViewById(R.id.conflictSummary);
        RecyclerView recyclerView = dialogView.findViewById(R.id.conflictList);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        ConflictAdapter adapter = new ConflictAdapter(preview.conflicts, summaryView);
        recyclerView.setAdapter(adapter);

        com.google.android.material.chip.ChipGroup chips = dialogView.findViewById(R.id.groupByChips);
        chips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            boolean byDate = checkedIds.contains(R.id.chipByDate);
            adapter.setGroupMode(byDate);
        });

        new MaterialAlertDialogBuilder(getContext())
                .setTitle(getString(R.string.podcast_addict_conflicts_title, preview.conflicts.size()))
                .setView(dialogView)
                .setNegativeButton(R.string.cancel_label, null)
                .setPositiveButton(R.string.confirm_label, (d, w) -> executeImport(preview))
                .show();
    }

    private String describePaState(PodcastAddictImporter.EpisodeState state) {
        if (state.played) return getString(R.string.podcast_addict_state_played);
        if (state.positionMs > 0) {
            int s = state.positionMs / 1000;
            return getString(R.string.podcast_addict_state_inprogress,
                    String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60));
        }
        return getString(R.string.podcast_addict_state_favorite);
    }

    // ── Grouped conflict adapter ─────────────────────────────────────────────

    private class ConflictAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_EPISODE = 1;

        private final List<PodcastAddictImporter.ConflictEpisode> allConflicts;
        private final TextView summaryView;
        private boolean groupByDate = false;

        // Sections and flat display list
        private final List<Section> sections = new ArrayList<>();
        private final List<Object> flatList = new ArrayList<>(); // Section | ConflictEpisode

        ConflictAdapter(List<PodcastAddictImporter.ConflictEpisode> conflicts, TextView summary) {
            this.allConflicts = conflicts;
            this.summaryView = summary;
            rebuild();
        }

        void setGroupMode(boolean byDate) {
            this.groupByDate = byDate;
            rebuild();
        }

        // ── Section ─────────────────────────────────────────────────────────

        private class Section {
            final String title;
            final List<PodcastAddictImporter.ConflictEpisode> episodes = new ArrayList<>();
            boolean expanded = false;

            Section(String title) { this.title = title; }

            /** null = mixed, true = all PA, false = all TP */
            Boolean groupState() {
                boolean anyPa = false, anyAp = false;
                for (PodcastAddictImporter.ConflictEpisode e : episodes) {
                    if (e.usePodcastAddict) anyPa = true; else anyAp = true;
                }
                if (anyPa && anyAp) return null;
                return anyPa;
            }

            void setAll(boolean usePa) {
                for (PodcastAddictImporter.ConflictEpisode e : episodes) e.usePodcastAddict = usePa;
            }
        }

        // ── Build ────────────────────────────────────────────────────────────

        private void rebuild() {
            sections.clear();
            java.util.Map<String, Section> map = new java.util.LinkedHashMap<>();

            long now = System.currentTimeMillis();
            long weekAgo  = now - 7L  * 86400000L;
            long monthAgo = now - 30L * 86400000L;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int thisYear = cal.get(java.util.Calendar.YEAR);

            for (PodcastAddictImporter.ConflictEpisode c : allConflicts) {
                String key;
                if (groupByDate) {
                    long date = c.paState.playbackDateMs;
                    if (date <= 0) {
                        key = getString(R.string.podcast_addict_date_unknown);
                    } else if (date >= weekAgo) {
                        key = getString(R.string.podcast_addict_date_this_week);
                    } else if (date >= monthAgo) {
                        key = getString(R.string.podcast_addict_date_this_month);
                    } else {
                        cal.setTimeInMillis(date);
                        key = cal.get(java.util.Calendar.YEAR) == thisYear
                                ? getString(R.string.podcast_addict_date_this_year)
                                : getString(R.string.podcast_addict_date_older);
                    }
                } else {
                    key = c.feedTitle;
                }
                if (!map.containsKey(key)) {
                    map.put(key, new Section(key));
                }
                map.get(key).episodes.add(c);
            }

            if (!groupByDate) {
                // Sort podcasts alphabetically
                List<Section> sorted = new ArrayList<>(map.values());
                sorted.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
                sections.addAll(sorted);
            } else {
                // Preserve chronological order of buckets
                sections.addAll(map.values());
            }

            refreshFlatList();
            updateSummary();
        }

        private void refreshFlatList() {
            flatList.clear();
            for (Section s : sections) {
                flatList.add(s);
                if (s.expanded) flatList.addAll(s.episodes);
            }
            notifyDataSetChanged();
        }

        private void updateSummary() {
            long paCount = allConflicts.stream().filter(c -> c.usePodcastAddict).count();
            summaryView.setText(getString(R.string.podcast_addict_summary,
                    (int) paCount, allConflicts.size()));
        }

        void setAll(boolean usePa) {
            for (PodcastAddictImporter.ConflictEpisode c : allConflicts) c.usePodcastAddict = usePa;
            refreshFlatList();
            updateSummary();
        }

        // ── Adapter ──────────────────────────────────────────────────────────

        @Override
        public int getItemViewType(int pos) {
            return flatList.get(pos) instanceof Section ? TYPE_HEADER : TYPE_EPISODE;
        }

        @Override
        public int getItemCount() { return flatList.size(); }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.item_conflict_section_header, parent, false));
            }
            return new EpisodeVH(inf.inflate(R.layout.item_conflict_episode, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH) bindHeader((HeaderVH) holder, (Section) flatList.get(position));
            else bindEpisode((EpisodeVH) holder, (PodcastAddictImporter.ConflictEpisode) flatList.get(position));
        }

        private void bindHeader(HeaderVH h, Section section) {
            h.title.setText(section.title);

            Boolean gs = section.groupState();
            String stateLabel = gs == null
                    ? getString(R.string.podcast_addict_section_mixed)
                    : (gs ? getString(R.string.podcast_addict_section_all_pa)
                           : getString(R.string.podcast_addict_section_all_ap));
            h.subtitle.setText(getString(R.string.podcast_addict_section_episodes,
                    section.episodes.size(), stateLabel));

            // Expand icon rotation
            h.expandIcon.setRotation(section.expanded ? 0f : -90f);

            // Group switch: checked = all PA; indeterminate shown via alpha
            h.groupSwitch.setOnCheckedChangeListener(null);
            h.groupSwitch.setChecked(gs == null || Boolean.TRUE.equals(gs));
            h.groupSwitch.setAlpha(gs == null ? 0.5f : 1f);
            h.groupSwitch.setOnCheckedChangeListener((btn, checked) -> {
                section.setAll(checked);
                int idx = flatList.indexOf(section);
                // Re-bind header to refresh subtitle + alpha
                notifyItemChanged(idx);
                // Re-bind visible episode children
                if (section.expanded) {
                    notifyItemRangeChanged(idx + 1, section.episodes.size());
                }
                updateSummary();
            });

            h.itemView.setOnClickListener(v -> {
                int idx = flatList.indexOf(section);
                section.expanded = !section.expanded;
                if (section.expanded) {
                    flatList.addAll(idx + 1, section.episodes);
                    notifyItemChanged(idx);
                    notifyItemRangeInserted(idx + 1, section.episodes.size());
                } else {
                    flatList.subList(idx + 1, idx + 1 + section.episodes.size()).clear();
                    notifyItemChanged(idx);
                    notifyItemRangeRemoved(idx + 1, section.episodes.size());
                }
            });
        }

        private void bindEpisode(EpisodeVH h, PodcastAddictImporter.ConflictEpisode conflict) {
            h.title.setText(conflict.episodeTitle);
            h.apState.setText(conflict.apStateDescription);
            h.paState.setText(describePaState(conflict.paState));
            h.toggle.setOnCheckedChangeListener(null);
            h.toggle.setChecked(conflict.usePodcastAddict);
            h.toggle.setOnCheckedChangeListener((btn, checked) -> {
                conflict.usePodcastAddict = checked;
                // Refresh parent header subtitle
                for (Section s : sections) {
                    int idx = flatList.indexOf(s);
                    if (s.episodes.contains(conflict) && idx >= 0) {
                        notifyItemChanged(idx);
                        break;
                    }
                }
                updateSummary();
            });
        }

        // ── ViewHolders ───────────────────────────────────────────────────────

        class HeaderVH extends RecyclerView.ViewHolder {
            final TextView title, subtitle;
            final android.widget.ImageView expandIcon;
            final MaterialSwitch groupSwitch;
            HeaderVH(View v) {
                super(v);
                title = v.findViewById(R.id.sectionTitle);
                subtitle = v.findViewById(R.id.sectionSubtitle);
                expandIcon = v.findViewById(R.id.expandIcon);
                groupSwitch = v.findViewById(R.id.groupSwitch);
            }
        }

        class EpisodeVH extends RecyclerView.ViewHolder {
            final TextView title, apState, paState;
            final MaterialSwitch toggle;
            EpisodeVH(View v) {
                super(v);
                title = v.findViewById(R.id.episodeTitle);
                apState = v.findViewById(R.id.apState);
                paState = v.findViewById(R.id.paState);
                toggle = v.findViewById(R.id.usePaSwitch);
            }
        }
    }

    private void executeImport(PodcastAddictImporter.ImportPreview preview) {
        progressDialog.show();
        disposable = io.reactivex.rxjava3.core.Completable
                .fromAction(() -> PodcastAddictImporter.executeImport(getContext(), preview))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    progressDialog.dismiss();
                    int totalStates = preview.nonConflictingStates.size()
                            + (int) preview.conflicts.stream().filter(c -> c.usePodcastAddict).count();
                    String msg = getString(R.string.podcast_addict_import_success,
                            preview.feeds.size(), totalStates);
                    Snackbar.make(getView(), msg, Snackbar.LENGTH_LONG).show();
                }, error -> {
                    progressDialog.dismiss();
                    String msg = getString(R.string.podcast_addict_import_error, error.getMessage());
                    new MaterialAlertDialogBuilder(getContext())
                            .setTitle(R.string.podcast_addict_import_title)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
    }

    private void forceRestart() {
        PackageManager pm = getContext().getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(getContext().getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().getApplicationContext().startActivity(intent);
        Runtime.getRuntime().exit(0);
    }

    private static class BackupDatabase extends ActivityResultContracts.CreateDocument {

        BackupDatabase() {
            super("application/x-sqlite3");
        }

        @NonNull
        @Override
        public Intent createIntent(@NonNull final Context context, @NonNull final String input) {
            return super.createIntent(context, input)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/x-sqlite3");
        }
    }

    private static class PickWritableFolder extends ActivityResultContracts.OpenDocumentTree {
        @NonNull
        @Override
        public Intent createIntent(@NonNull final Context context, @Nullable final Uri input) {
            return super.createIntent(context, input)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
    }

    private enum Export {
        OPML(CONTENT_TYPE_OPML, DEFAULT_OPML_OUTPUT_NAME, R.string.opml_export_label),
        HTML(CONTENT_TYPE_HTML, DEFAULT_HTML_OUTPUT_NAME, R.string.html_export_label),
        FAVORITES(CONTENT_TYPE_HTML, DEFAULT_FAVORITES_OUTPUT_NAME, R.string.favorites_export_label);

        final String contentType;
        final String outputNameTemplate;
        @StringRes
        final int labelResId;

        Export(String contentType, String outputNameTemplate, int labelResId) {
            this.contentType = contentType;
            this.outputNameTemplate = outputNameTemplate;
            this.labelResId = labelResId;
        }
    }
}
