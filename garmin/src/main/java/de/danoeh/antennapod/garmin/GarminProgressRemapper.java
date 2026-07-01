package de.danoeh.antennapod.garmin;

import java.util.List;
import java.util.Map;

/**
 * Rewrites the playback positions in a PortCast document received from the watch
 * from <em>rendered</em> time (trimmed + speed-adjusted) back to original episode
 * time, in place, using each episode's render manifest.
 *
 * <p>Operates on the nested {@link Map}/{@link List} structures the Connect IQ
 * Mobile SDK delivers — pure Java (no Android, no JSON lib), so it is unit-tested
 * directly. {@link GarminPortcastBridge} calls this before serializing the document
 * for {@code PortcastImporter}.
 */
public final class GarminProgressRemapper {

    private GarminProgressRemapper() {
    }

    /**
     * Remap every episode's {@code positionSeconds} (and event positions) to
     * original time. Episodes whose guid has no known manifest are left unchanged.
     */
    @SuppressWarnings("unchecked")
    public static void remap(Map<String, Object> doc, GarminManifestLookup lookup) {
        Object episodesObj = doc.get("episodes");
        if (!(episodesObj instanceof List)) {
            return;
        }
        for (Object epObj : (List<Object>) episodesObj) {
            if (!(epObj instanceof Map)) {
                continue;
            }
            Map<String, Object> ep = (Map<String, Object>) epObj;
            Object guidObj = ep.get("guid");
            if (!(guidObj instanceof String)) {
                continue;
            }
            GarminRenderManifest manifest = lookup.get((String) guidObj);
            if (manifest == null) {
                continue;
            }
            remapField(ep, manifest);
            Object eventsObj = ep.get("events");
            if (eventsObj instanceof List) {
                for (Object evObj : (List<Object>) eventsObj) {
                    if (evObj instanceof Map) {
                        remapField((Map<String, Object>) evObj, manifest);
                    }
                }
            }
        }
    }

    /**
     * Remap a single {@code positionSeconds} field in place, if present numeric.
     */
    private static void remapField(Map<String, Object> obj, GarminRenderManifest manifest) {
        Object pos = obj.get("positionSeconds");
        if (pos instanceof Number) {
            double rendered = ((Number) pos).doubleValue();
            obj.put("positionSeconds", GarminPositionMapper.renderedToOriginal(rendered, manifest));
        }
    }
}
