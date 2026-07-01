package de.danoeh.antennapod.event;

/**
 * Posted when the backend confirms an episode has been analyzed but produced
 * no skippable segments (intentionally clean: no intros/ads/outros detected).
 *
 * <p>Distinct from {@link TrimSegmentsUnlockedEvent} so subscribers can render a
 * different snackbar / badge state for "analyzed → nothing to skip" versus
 * "analyzed → here are the skip points". Resolves the dangling "Mapping…"
 * snackbar that {@link TrimAnalyzeQueuedEvent} would otherwise leave hanging.
 */
public class TrimAnalyzedEmptyEvent {
    public final String podcastTitle;

    public TrimAnalyzedEmptyEvent(String podcastTitle) {
        this.podcastTitle = podcastTitle == null ? "" : podcastTitle;
    }
}
