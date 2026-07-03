package de.danoeh.antennapod;

/**
 * Posted on the EventBus when a PortCast progress document from the Garmin watch
 * has been applied to the library ({@link TrimGarminWatchSync}), so UI waiting on
 * a "Get watch progress" request can show the real outcome instead of guessing.
 */
public class GarminWatchProgressEvent {

    /** Episode states applied to the library, or -1 when the apply failed. */
    public final int appliedCount;

    public GarminWatchProgressEvent(int appliedCount) {
        this.appliedCount = appliedCount;
    }
}
