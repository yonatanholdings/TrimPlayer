package de.danoeh.antennapod.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;

/**
 * Translucent dispatcher for share/view intents that contain an importable file
 * (OPML, Podcast Addict ZIP backup, or TrimPlayer SQLite backup). Forwards the URI
 * to {@link PreferenceActivity}, which routes it through the unified import detection
 * in ImportExportPreferencesFragment.
 */
public class ShareImportActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri uri = extractUri(getIntent());
        if (uri == null) {
            Toast.makeText(this, R.string.opml_import_error_no_file, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Intent target = new Intent(this, PreferenceActivity.class);
        target.putExtra(PreferenceActivity.OPEN_IMPORT_EXPORT, true);
        target.putExtra(PreferenceActivity.IMPORT_URI, uri);
        target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(target);
        finish();
    }

    private static Uri extractUri(Intent intent) {
        if (intent == null) {
            return null;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                return stream;
            }
            String extraText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (extraText != null) {
                return Uri.parse(extraText);
            }
            return null;
        }
        return intent.getData();
    }
}
