package de.danoeh.antennapod.portcast;

/**
 * One row in the import-conflict dialog. Source-agnostic — the same shape
 * is filled in from PodcastAddict and PortCast preview data so the dialog
 * doesn't need to fork per source.
 *
 * <p>{@link #useIncoming} carries the user's resolution: {@code true}
 * means "apply the value from the imported file"; {@code false} means
 * "keep whatever TrimPlayer already has."
 */
public class ConflictRow {
    public String episodeTitle;
    public String feedTitle;
    public String apStateDescription;
    public String incomingStateDescription;
    public long lastPlayedMs;
    public boolean useIncoming = true;
}
