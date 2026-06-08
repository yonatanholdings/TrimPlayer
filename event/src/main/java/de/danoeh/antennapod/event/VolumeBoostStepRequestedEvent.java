package de.danoeh.antennapod.event;

/**
 * Posted by the UI (foreground volume-up key intercept) to ask PlaybackService to
 * step the currently-playing feed's volume boost one rung up or down. Used because
 * the broadcast-based "redundant volume press at the ceiling" gesture can't fire on
 * devices that don't emit a VOLUME_CHANGED broadcast for a no-op press at max.
 */
public class VolumeBoostStepRequestedEvent {
    public final boolean up;

    public VolumeBoostStepRequestedEvent(boolean up) {
        this.up = up;
    }
}
