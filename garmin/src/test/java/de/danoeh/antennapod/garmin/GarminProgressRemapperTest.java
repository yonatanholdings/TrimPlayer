package de.danoeh.antennapod.garmin;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Verifies the watch-doc position remap (rendered time -> original time) on the
 * nested Map/List structures the Connect IQ SDK delivers.
 */
public class GarminProgressRemapperTest {

    private static final double EPS = 1e-6;

    /** Same render as the mapper tests: drop [0,20]+[60,75] of 156.03s, 1.5x. */
    private GarminManifestLookup lookupWith(String guid) {
        List<GarminRenderManifest.Range> skipped = Arrays.asList(
                new GarminRenderManifest.Range(0, 20),
                new GarminRenderManifest.Range(60, 75));
        GarminRenderManifest m = GarminRenderManifest.fromSkipped(guid, 1.5, skipped, 156.03);
        return g -> g.equals(guid) ? m : null;
    }

    private Map<String, Object> episode(String guid, double posSeconds) {
        Map<String, Object> ep = new HashMap<>();
        ep.put("guid", guid);
        ep.put("positionSeconds", posSeconds);
        return ep;
    }

    private Map<String, Object> doc(Map<String, Object>... episodes) {
        Map<String, Object> d = new HashMap<>();
        d.put("episodes", new ArrayList<>(Arrays.asList(episodes)));
        return d;
    }

    @Test
    public void remapsEpisodePosition() {
        // 10s rendered -> 15s concat -> 35s original (see GarminPositionMapperTest).
        Map<String, Object> ep = episode("g1", 10.0);
        GarminProgressRemapper.remap(doc(ep), lookupWith("g1"));
        assertEquals(35.0, ((Number) ep.get("positionSeconds")).doubleValue(), EPS);
    }

    @Test
    public void remapsEventPositions() {
        Map<String, Object> ev = new HashMap<>();
        ev.put("type", "pause");
        ev.put("positionSeconds", 30.0); // -> 45s concat -> 80s original
        Map<String, Object> ep = episode("g1", 10.0);
        ep.put("events", new ArrayList<>(Arrays.asList((Object) ev)));

        GarminProgressRemapper.remap(doc(ep), lookupWith("g1"));
        assertEquals(80.0, ((Number) ev.get("positionSeconds")).doubleValue(), EPS);
    }

    @Test
    public void leavesUnknownGuidUnchanged() {
        Map<String, Object> ep = episode("other", 10.0);
        GarminProgressRemapper.remap(doc(ep), lookupWith("g1"));
        assertEquals(10.0, ((Number) ep.get("positionSeconds")).doubleValue(), EPS);
    }

    @Test
    public void handlesIntegerPositions() {
        // SDK may deliver whole numbers as Integer/Long, not Double.
        Map<String, Object> ep = new HashMap<>();
        ep.put("guid", "g1");
        ep.put("positionSeconds", 10); // int
        GarminProgressRemapper.remap(doc(ep), lookupWith("g1"));
        assertEquals(35.0, ((Number) ep.get("positionSeconds")).doubleValue(), EPS);
    }

    @Test
    public void toleratesMissingEpisodesArray() {
        Map<String, Object> d = new HashMap<>();
        d.put("portcast", "0.2.0");
        GarminProgressRemapper.remap(d, lookupWith("g1")); // must not throw
    }

    @Test
    public void toleratesEpisodeWithoutPosition() {
        Map<String, Object> ep = new HashMap<>();
        ep.put("guid", "g1"); // no positionSeconds (e.g. status-only)
        GarminProgressRemapper.remap(doc(ep), lookupWith("g1")); // must not throw
    }
}
