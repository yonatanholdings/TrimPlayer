package de.danoeh.antennapod;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.danoeh.antennapod.garmin.GarminAudioRenderPlan;
import de.danoeh.antennapod.garmin.GarminCompanionManager;
import de.danoeh.antennapod.garmin.GarminManifestLookup;
import de.danoeh.antennapod.garmin.GarminPortcastBridge;
import de.danoeh.antennapod.garmin.GarminRenderManifest;
import de.danoeh.antennapod.garmin.GarminRenderManifestStore;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.playback.service.trim.TrimSegmentCache;
import de.danoeh.antennapod.storage.database.DBReader;

/**
 * Receives the watch's PortCast progress documents (via
 * {@link GarminCompanionManager}) and applies them to the library.
 *
 * <p>The watch reports positions in the <em>rendered</em> timeline (trimmed +
 * sped audio). {@link GarminPortcastBridge} inverts that using a per-episode
 * render manifest. Phone-rendered episodes have one persisted in
 * {@link GarminRenderManifestStore}; <b>server-rendered</b> episodes (the
 * production path — the watch downloads from api.trimplayer.com) don't, so this
 * class rebuilds an equivalent manifest from what the phone already knows:
 * the episode's cached skip segments ({@link TrimSegmentCache} — the same
 * canonical segments the backend rendered with) and the feed's synced playback
 * rate (the same account pref the backend baked into the render URL; feeds on
 * the global default render at 1.0×, matching the backend's query default).
 */
public final class TrimGarminWatchSync implements GarminCompanionManager.WatchMessageHandler {

    private static final String TAG = "TrimGarminWatchSync";

    private final Context context;

    private TrimGarminWatchSync(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Start listening for watch messages. Call once from Application.onCreate;
     *  cheap and failure-tolerant (no Garmin Connect app → logged no-op). */
    public static void start(Context context) {
        GarminCompanionManager.start(context, new TrimGarminWatchSync(context));
    }

    @Override
    public void onWatchMessage(Map<String, Object> portcastDoc) {
        GarminManifestLookup lookup = buildLookup(portcastDoc);
        new GarminPortcastBridge(context, lookup).applyFromWatchMessage(portcastDoc);
    }

    /** Compose the manifest lookup for this document: the persisted store first
     *  (phone renders), then per-guid manifests rebuilt from cached segments for
     *  the server-rendered episodes in the doc. */
    private GarminManifestLookup buildLookup(Map<String, Object> doc) {
        GarminRenderManifestStore store = new GarminRenderManifestStore(context);
        Map<String, GarminRenderManifest> rebuilt = new HashMap<>();

        Object episodesObj = doc.get("episodes");
        if (episodesObj instanceof List) {
            for (Object epObj : (List<?>) episodesObj) {
                if (!(epObj instanceof Map)) {
                    continue;
                }
                Map<?, ?> ep = (Map<?, ?>) epObj;
                Object guidObj = ep.get("guid");
                if (!(guidObj instanceof String) || store.get((String) guidObj) != null) {
                    continue;
                }
                Object enclosureObj = ep.get("enclosureUrl");
                String enclosure = (enclosureObj instanceof String) ? (String) enclosureObj : null;
                GarminRenderManifest manifest = rebuildManifest((String) guidObj, enclosure);
                if (manifest != null) {
                    rebuilt.put((String) guidObj, manifest);
                }
            }
        }

        return guid -> {
            GarminRenderManifest m = store.get(guid);
            return (m != null) ? m : rebuilt.get(guid);
        };
    }

    /** Rebuild the render manifest for a server-rendered episode, or null when
     *  the phone can't (episode unknown locally / no cached segments / no
     *  duration). Null means positions pass through unmapped — the importer's
     *  conflict handling still applies, just in rendered time; better than
     *  dropping the report. */
    private GarminRenderManifest rebuildManifest(String portcastGuid, String enclosureUrl) {
        if (enclosureUrl == null || enclosureUrl.isEmpty()) {
            return null;
        }
        try {
            FeedItem item = DBReader.getFeedItemByGuidOrEpisodeUrl(null, enclosureUrl);
            if (item == null || item.getMedia() == null || item.getMedia().getDuration() <= 0) {
                return null;
            }
            List<TrimClient.Segment> segments =
                    TrimSegmentCache.get(context, item.getItemIdentifier());
            if (segments == null) {
                return null; // no local segment knowledge — can't reconstruct
            }
            List<GarminRenderManifest.Range> skipped = new ArrayList<>();
            for (TrimClient.Segment s : segments) {
                if (s != null && s.end > s.start) {
                    skipped.add(new GarminRenderManifest.Range(s.start, s.end));
                }
            }
            // The backend renders at the feed's synced playback rate; feeds on
            // the global default have no synced pref and render at 1.0x.
            double speed = 1.0;
            if (item.getFeed() != null && item.getFeed().getPreferences() != null) {
                float feedSpeed = item.getFeed().getPreferences().getFeedPlaybackSpeed();
                if (feedSpeed != FeedPreferences.SPEED_USE_GLOBAL) {
                    speed = feedSpeed;
                }
            }
            double durationSeconds = item.getMedia().getDuration() / 1000.0;
            return GarminAudioRenderPlan
                    .create(portcastGuid, speed, skipped, durationSeconds)
                    .manifest();
        } catch (Exception e) {
            Log.w(TAG, "manifest rebuild failed for " + portcastGuid + ": " + e.getMessage());
            return null;
        }
    }
}
