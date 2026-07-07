package de.danoeh.antennapod.event;

/**
 * Posted after a bookmark of the given episode was added, edited, or deleted,
 * so any open bookmark list refreshes.
 */
public class BookmarksChangedEvent {
    public final long feedItemId;

    public BookmarksChangedEvent(long feedItemId) {
        this.feedItemId = feedItemId;
    }
}
