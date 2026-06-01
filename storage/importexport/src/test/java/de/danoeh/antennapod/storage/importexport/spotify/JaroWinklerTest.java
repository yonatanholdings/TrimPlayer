package de.danoeh.antennapod.storage.importexport.spotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the Jaro-Winkler implementation against published reference values
 * and against the kinds of near-misses we expect from Spotify ↔ PodcastIndex
 * publisher-name discrepancies (the practical use case in
 * {@link PodcastIndexResolver}).
 *
 * <p>Pure Java — no Robolectric needed.
 */
public class JaroWinklerTest {

    private static final double EPS = 0.005;

    @Test
    public void identicalStringsScoreOne() {
        assertEquals(1.0, JaroWinkler.similarity("acquired", "acquired"), 0.0);
    }

    @Test
    public void bothEmptyScoresOne() {
        assertEquals(1.0, JaroWinkler.similarity("", ""), 0.0);
    }

    @Test
    public void nullsAreTreatedAsEmpty() {
        assertEquals(1.0, JaroWinkler.similarity(null, null), 0.0);
        assertEquals(0.0, JaroWinkler.similarity(null, "anything"), 0.0);
        assertEquals(0.0, JaroWinkler.similarity("anything", null), 0.0);
    }

    @Test
    public void disjointStringsScoreZero() {
        assertEquals(0.0, JaroWinkler.similarity("abc", "xyz"), 0.0);
    }

    @Test
    public void matchesWikipediaWorkedExample() {
        // MARTHA vs MARHTA — published Jaro-Winkler value is ~0.961.
        assertEquals(0.961, JaroWinkler.similarity("MARTHA", "MARHTA"), EPS);
    }

    @Test
    public void matchesPublishedDixonValue() {
        // DIXON vs DICKSONX — published Jaro value 0.767, Jaro-Winkler 0.813.
        assertEquals(0.813, JaroWinkler.similarity("DIXON", "DICKSONX"), EPS);
    }

    @Test
    public void publisherNearMissCrossesAuthorThreshold() {
        // Real-world shape from a Spotify export: Spotify spells the publisher
        // one way, PodcastIndex's catalog has it spelled slightly differently.
        // Both should still pass the 0.85 author threshold in
        // PodcastIndexResolver.
        double score = JaroWinkler.similarity(
                "ben gilbert and david rosenthal",
                "ben gilbert & david rosenthal");
        assertTrue("expected ≥0.85, got " + score, score >= 0.85);
    }

    @Test
    public void wronglyMatchedPublisherFallsBelowThreshold() {
        // A different "The Daily" with a different publisher should not
        // accidentally match NYT's. The author threshold is what saves us.
        double score = JaroWinkler.similarity(
                "the new york times",
                "tech podcast network");
        assertTrue("expected <0.85, got " + score, score < 0.85);
    }
}
