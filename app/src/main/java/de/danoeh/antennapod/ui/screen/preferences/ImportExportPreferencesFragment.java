package de.danoeh.antennapod.ui.screen.preferences;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;
import com.google.android.material.snackbar.Snackbar;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.AnalyticsEvent;
import de.danoeh.antennapod.importflow.ImportFlowController;
import de.danoeh.antennapod.migration.SpotifyMigrationActivity;
import de.danoeh.antennapod.onboarding.OnboardingActivity;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.importexport.AutomaticDatabaseExportWorker;
import de.danoeh.antennapod.storage.importexport.DatabaseExporter;
import de.danoeh.antennapod.storage.importexport.FavoritesWriter;
import de.danoeh.antennapod.storage.importexport.HtmlWriter;
import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.storage.importexport.OpmlWriter;
import de.danoeh.antennapod.storage.importexport.PortcastExporter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;

import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
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
    private static final String PREF_PORTCAST_EXPORT = "prefPortcastExport";
    private static final String PREF_COMING_FROM_SPOTIFY = "prefComingFromSpotify";
    private static final String PREF_IMPORT = "prefImport";
    private static final String PREF_ONBOARDING = "prefOnboarding";
    private static final String DEFAULT_OPML_OUTPUT_NAME = "trimplayer-feeds-%s.opml";
    private static final String CONTENT_TYPE_OPML = "text/x-opml";
    private static final String DEFAULT_HTML_OUTPUT_NAME = "trimplayer-feeds-%s.html";
    private static final String CONTENT_TYPE_HTML = "text/html";
    private static final String DEFAULT_FAVORITES_OUTPUT_NAME = "trimplayer-favorites-%s.html";
    private static final String DEFAULT_PORTCAST_OUTPUT_NAME = "trimplayer-feeds-%s.portcast.json";
    private static final String CONTENT_TYPE_PORTCAST = "application/json";
    private static final String DATABASE_EXPORT_FILENAME = "TrimPlayerBackup-%s.db";

    // Declared before the launchers: unifiedImportLauncher's callback references it.
    private ImportFlowController importController;

    private final ActivityResultLauncher<Intent> chooseOpmlExportPathLauncher =
            registerForActivityResult(new StartActivityForResult(),
                    result -> exportToDocument(result, Export.OPML));
    private final ActivityResultLauncher<Intent> chooseHtmlExportPathLauncher =
            registerForActivityResult(new StartActivityForResult(),
                    result -> exportToDocument(result, Export.HTML));
    private final ActivityResultLauncher<Intent> chooseFavoritesExportPathLauncher =
            registerForActivityResult(new StartActivityForResult(),
                    result -> exportToDocument(result, Export.FAVORITES));
    private final ActivityResultLauncher<Intent> choosePortcastExportPathLauncher =
            registerForActivityResult(new StartActivityForResult(),
                    result -> exportToDocument(result, Export.PORTCAST));
    private final ActivityResultLauncher<String> backupDatabaseLauncher =
            registerForActivityResult(new BackupDatabase(), this::backupDatabaseResult);
    private final ActivityResultLauncher<String> unifiedImportLauncher =
            registerForActivityResult(new GetContent(), uri -> importController.route(uri));
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
        // Import detection + routing is shared with the first-run onboarding screen.
        // Settings keeps the full-restore default for a .db onto an empty library
        // (disaster recovery), so preferMergeForFreshLibrary = false.
        importController = new ImportFlowController((AppCompatActivity) requireActivity(), null, false);

        if (savedInstanceState == null && getArguments() != null) {
            Uri pendingImport = getArguments().getParcelable(ARG_IMPORT_URI);
            if (pendingImport != null) {
                getArguments().remove(ARG_IMPORT_URI);
                importController.route(pendingImport);
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
        if (importController != null) {
            importController.dispose();
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
        findPreference(PREF_ONBOARDING).setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(getContext(), OnboardingActivity.class));
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
        findPreference(PREF_PORTCAST_EXPORT).setOnPreferenceClickListener(
                preference -> {
                    openExportPathPicker(Export.PORTCAST, choosePortcastExportPathLauncher);
                    return true;
                });
        findPreference(PREF_COMING_FROM_SPOTIFY).setOnPreferenceClickListener(preference -> {
            showComingFromSpotifyDialog();
            return true;
        });
    }

    private void showComingFromSpotifyDialog() {
        // "Get the extension" hidden until the Chrome Web Store listing is public.
        // "I have the file" stays — users handed a beta build of the extension
        // privately can still use the file-transfer path.
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.coming_from_spotify_title)
                .setMessage(R.string.coming_from_spotify_body)
                .setPositiveButton(R.string.coming_from_spotify_sign_in,
                        (d, w) -> startActivity(
                                new Intent(getContext(), SpotifyMigrationActivity.class)))
                .setNeutralButton(R.string.coming_from_spotify_have_file,
                        (d, w) -> unifiedImportLauncher.launch("*/*"))
                .show();
    }

    private String dateStampFilename(String fname) {
        return String.format(fname, new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
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
                    EventBus.getDefault().post(AnalyticsEvent.exportCompleted("database"));
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
                    EventBus.getDefault().post(AnalyticsEvent.exportCompleted(exportType.analyticsFormat));
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
                    EventBus.getDefault().post(AnalyticsEvent.exportCompleted(exportType.analyticsFormat));
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
                case PORTCAST:
                    PortcastExporter.writeDocument(writer, BuildConfig.VERSION_NAME);
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
        OPML(CONTENT_TYPE_OPML, DEFAULT_OPML_OUTPUT_NAME, R.string.opml_export_label, "opml"),
        HTML(CONTENT_TYPE_HTML, DEFAULT_HTML_OUTPUT_NAME, R.string.html_export_label, "html"),
        FAVORITES(CONTENT_TYPE_HTML, DEFAULT_FAVORITES_OUTPUT_NAME, R.string.favorites_export_label, "favorites"),
        PORTCAST(CONTENT_TYPE_PORTCAST, DEFAULT_PORTCAST_OUTPUT_NAME, R.string.portcast_export_label, "portcast");

        final String contentType;
        final String outputNameTemplate;
        @StringRes
        final int labelResId;
        /** export_completed analytics label. */
        final String analyticsFormat;

        Export(String contentType, String outputNameTemplate, int labelResId, String analyticsFormat) {
            this.contentType = contentType;
            this.outputNameTemplate = outputNameTemplate;
            this.labelResId = labelResId;
            this.analyticsFormat = analyticsFormat;
        }
    }
}
