package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.util.Log;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class DatabaseExporter {
    private static final String TAG = "DatabaseExporter";
    private static final String TEMP_DB_NAME = PodDBAdapter.DATABASE_NAME + "_tmp";

    public static void importBackup(Uri inputUri, Context context) throws IOException {
        InputStream inputStream = null;
        File tempDB = context.getDatabasePath(TEMP_DB_NAME);
        try {
            inputStream = context.getContentResolver().openInputStream(inputUri);
            FileUtils.copyInputStreamToFile(inputStream, tempDB);

            SQLiteDatabase db;
            try {
                db = SQLiteDatabase.openDatabase(tempDB.getAbsolutePath(),
                        null, SQLiteDatabase.OPEN_READONLY);
            } catch (SQLiteException e) {
                Log.e(TAG, Log.getStackTraceString(e));
                throw new IOException(context.getString(R.string.import_not_a_database));
            }
            if (db.getVersion() > PodDBAdapter.VERSION) {
                db.close();
                throw new IOException(context.getString(R.string.import_no_downgrade));
            }
            db.close();

            File currentDB = context.getDatabasePath(PodDBAdapter.DATABASE_NAME);
            boolean success = currentDB.delete();
            if (!success) {
                throw new IOException("Unable to delete old database");
            }
            FileUtils.moveFile(tempDB, currentDB);
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw e;
        } finally {
            IOUtils.closeQuietly(inputStream);
            // Clean up temp file; Android SQLite may rename corrupt DBs to .corrupt
            if (tempDB.exists()) {
                tempDB.delete();
            }
            new File(tempDB.getAbsolutePath() + ".corrupt").delete();
        }
    }
}
