package de.danoeh.antennapod.garmin;

/** Looks up a render manifest by PortCast episode guid. Lets the position-remap
 *  logic be tested with an in-memory provider instead of the SharedPreferences
 *  store. Implemented by {@link GarminRenderManifestStore}. Returns null when no
 *  manifest is known for the guid. Pure Java (no Android deps). */
public interface GarminManifestLookup {
    GarminRenderManifest get(String guid);
}
