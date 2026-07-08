package de.danoeh.antennapod.model.feed;

/**
 * A user-created marker at a playback position inside an episode,
 * with an optional free-text note.
 */
public class Bookmark {
    private final long id;
    private final long feedItemId;
    private final int position;
    private final String note;
    private final long createdAt;
    private final String syncId;

    public Bookmark(long id, long feedItemId, int position, String note, long createdAt) {
        this(id, feedItemId, position, note, createdAt, null);
    }

    public Bookmark(long id, long feedItemId, int position, String note, long createdAt, String syncId) {
        this.id = id;
        this.feedItemId = feedItemId;
        this.position = position;
        this.note = note == null ? "" : note;
        this.createdAt = createdAt;
        this.syncId = syncId;
    }

    public long getId() {
        return id;
    }

    public long getFeedItemId() {
        return feedItemId;
    }

    /** Playback position in milliseconds. */
    public int getPosition() {
        return position;
    }

    /** User note, never null (empty when the user didn't add one). */
    public String getNote() {
        return note;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** Stable cross-device identifier used by account sync; may be null on rows
     *  read by code paths that don't need it. */
    public String getSyncId() {
        return syncId;
    }
}
