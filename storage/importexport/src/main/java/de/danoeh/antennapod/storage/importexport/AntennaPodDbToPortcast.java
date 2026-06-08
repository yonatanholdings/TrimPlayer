package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.IOException;

/**
 * Translates an AntennaPod/TrimPlayer database backup into a PortCast JSON
 * document so it can be merged into the current library through the existing,
 * additive PortCast import pipeline ({@link PortcastImporter}) instead of the
 * destructive whole-file replace done by {@link DatabaseExporter#importBackup}.
 *
 * <p>The heavy lifting is reused: {@link BackupDbReader} reads the backup into
 * model objects and {@link PortcastExporter#buildDocument} serializes them — the
 * same serializer the live-library export uses, so the output round-trips
 * cleanly back through {@code previewImport}.
 */
public final class AntennaPodDbToPortcast {
    /** PortCast documents require an owner; a backup carries no identity of its own. */
    private static final String OWNER = "TrimPlayer User";

    private AntennaPodDbToPortcast() { }

    /**
     * Read the backup at {@code backupUri} and return an equivalent PortCast JSON
     * string. Must run off the main thread (reads SQLite).
     *
     * <p>Global playback preferences are intentionally dropped: they aren't part
     * of a {@code .db} backup (they live in SharedPreferences) and a merge must
     * not change the device's current global settings. Per-feed preferences are
     * kept — they're feed-level data the user expects restored.
     */
    public static String toPortcastJson(Context context, Uri backupUri, String generatorVersion)
            throws IOException {
        BackupDbReader.Library lib = BackupDbReader.readFromUri(context, backupUri);
        return toPortcastJson(lib, generatorVersion);
    }

    /** Serialize an already-read backup. Package-visible so it's unit-testable
     *  without an Android {@code Uri}/SQLite. */
    static String toPortcastJson(BackupDbReader.Library lib, String generatorVersion)
            throws IOException {
        // Neutral globals — stripped below, but buildDocument requires a value.
        PortcastExporter.GlobalPrefs neutralGlobals =
                new PortcastExporter.GlobalPrefs(0f, -1, -1, false);
        JSONObject doc = PortcastExporter.buildDocument(lib.feeds, lib.episodes, lib.queue,
                lib.favoriteIds, OWNER, neutralGlobals, generatorVersion);
        JSONObject prefs = doc.optJSONObject("preferences");
        if (prefs != null) {
            prefs.remove("global");
        }
        return doc.toString();
    }
}
