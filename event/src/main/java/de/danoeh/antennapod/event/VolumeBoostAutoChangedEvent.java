package de.danoeh.antennapod.event;

import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;

/**
 * Posted when PlaybackService auto-adjusts a feed's volumeAdaptionSetting
 * because the user pressed volume-up/down past the OS ceiling/floor. The UI
 * shows a Snackbar with an undo action so a misfire is a single tap away.
 */
public class VolumeBoostAutoChangedEvent {
    public final long feedId;
    public final String podcastTitle;
    public final VolumeAdaptionSetting newSetting;
    /** The setting we changed FROM — needed so the UI's Undo button can revert. */
    public final VolumeAdaptionSetting previousSetting;

    public VolumeBoostAutoChangedEvent(long feedId, String podcastTitle,
                                       VolumeAdaptionSetting newSetting,
                                       VolumeAdaptionSetting previousSetting) {
        this.feedId = feedId;
        this.podcastTitle = podcastTitle == null ? "" : podcastTitle;
        this.newSetting = newSetting;
        this.previousSetting = previousSetting;
    }
}
