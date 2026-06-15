package de.danoeh.antennapod.garmin;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Verifies the rendered-time -> original-time inversion against the forward
 * render the backend/stub performs (drop skipped ranges, concat, atempo).
 */
public class GarminPositionMapperTest {

    private static final double EPS = 1e-6;

    /** Episode-1 case used throughout the project: 156s source, drop [0,20]+[60,75], 1.5x. */
    private GarminRenderManifest episode1() {
        List<GarminRenderManifest.Range> skipped = Arrays.asList(
                new GarminRenderManifest.Range(0, 20),
                new GarminRenderManifest.Range(60, 75));
        return GarminRenderManifest.fromSkipped("guid-1", 1.5, skipped, 156.03);
    }

    @Test
    public void keptRangesAreComplementOfSkipped() {
        GarminRenderManifest m = episode1();
        // kept = [20,60) + [75,156.03)
        assertEquals(2, m.keptRanges.size());
        assertEquals(20.0, m.keptRanges.get(0).startSeconds, EPS);
        assertEquals(60.0, m.keptRanges.get(0).endSeconds, EPS);
        assertEquals(75.0, m.keptRanges.get(1).startSeconds, EPS);
        assertEquals(156.03, m.keptRanges.get(1).endSeconds, EPS);
    }

    @Test
    public void renderedDurationMatchesObservedOutput() {
        // kept length = 40 + 81.03 = 121.03; /1.5 = 80.6867 -> matches the 1:20.69
        // we observed from ffmpeg in the stub and the backend.
        assertEquals(80.686667, episode1().renderedDurationSeconds(), 1e-4);
    }

    @Test
    public void startMapsToFirstKeptStart() {
        assertEquals(20.0, GarminPositionMapper.renderedToOriginal(0, episode1()), EPS);
    }

    @Test
    public void positionInFirstKeptRange() {
        // 10s into the rendered file -> 15s of concat audio -> 20 + 15 = 35s original.
        assertEquals(35.0, GarminPositionMapper.renderedToOriginal(10.0, episode1()), EPS);
    }

    @Test
    public void positionAfterTheSkipBoundary() {
        // 30s rendered -> 45s concat. First kept range is 40s long, so 5s into the
        // second kept range which starts at 75 -> 80s original.
        assertEquals(80.0, GarminPositionMapper.renderedToOriginal(30.0, episode1()), EPS);
    }

    @Test
    public void endClampsToLastKeptEnd() {
        assertEquals(156.03, GarminPositionMapper.renderedToOriginal(999.0, episode1()), EPS);
    }

    @Test
    public void noSkipsNoSpeedIsIdentity() {
        GarminRenderManifest m = GarminRenderManifest.fromSkipped("g", 1.0,
                java.util.Collections.emptyList(), 100.0);
        assertEquals(42.0, GarminPositionMapper.renderedToOriginal(42.0, m), EPS);
    }

    @Test
    public void roundTripForwardThenInverse() {
        // Forward-render a known original position, then invert it.
        GarminRenderManifest m = episode1();
        double original = 100.0;                 // in second kept range [75,156.03)
        // forward: concat offset = 40 (first kept) + (100-75) = 65; rendered = 65/1.5
        double rendered = (40.0 + (100.0 - 75.0)) / 1.5;
        assertEquals(original, GarminPositionMapper.renderedToOriginal(rendered, m), 1e-4);
    }
}
