package de.danoeh.antennapod.garmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Integration test: proves the companion's pieces compose correctly end-to-end
 * (plan → manifest → render → watch reports a position → remap → original time),
 * rather than only verifying each in isolation.
 */
public class GarminPipelineIntegrationTest {

    private static final double EPS = 1e-6;

    private GarminAudioRenderPlan episode1Plan() {
        List<GarminRenderManifest.Range> skipped = Arrays.asList(
                new GarminRenderManifest.Range(0, 20),
                new GarminRenderManifest.Range(60, 75));
        return GarminAudioRenderPlan.create("guid-1", 1.5, skipped, 156.03);
    }

    /**
     * Forward map an original position to rendered, send it back through the remap
     * the way the watch + bridge would, and confirm we recover the original. This
     * is the contract the whole resume feature rests on.
     */
    @Test
    public void originalRoundTripsThroughRenderAndRemap() {
        GarminAudioRenderPlan plan = episode1Plan();
        GarminRenderManifest manifest = plan.manifest();
        GarminManifestLookup lookup = g -> g.equals("guid-1") ? manifest : null;

        for (double original : new double[] {25.0, 40.0, 59.9, 80.0, 100.0, 156.0}) {
            // Phone-side: where would this land in the rendered file?
            double rendered = GarminPositionMapper.originalToRendered(original, manifest);

            // Watch reports that rendered position in a PortCast doc...
            Map<String, Object> ep = new HashMap<>();
            ep.put("guid", "guid-1");
            ep.put("positionSeconds", rendered);
            Map<String, Object> doc = new HashMap<>();
            doc.put("episodes", new ArrayList<>(Arrays.asList((Object) ep)));

            // ...bridge remaps it back to original time.
            GarminProgressRemapper.remap(doc, lookup);
            double recovered = ((Number) ep.get("positionSeconds")).doubleValue();

            assertEquals("round-trip for original=" + original, original, recovered, 1e-4);
        }
    }

    /** A position inside a removed range maps to the following cut point, and the
     *  recovered original is the start of the next kept range (content was cut). */
    @Test
    public void positionInsideSkippedRangeMapsToCut() {
        GarminRenderManifest m = episode1Plan().manifest();
        // 65s is inside the removed [60,75] range; next kept range starts at 75.
        double rendered = GarminPositionMapper.originalToRendered(65.0, m);
        assertEquals(75.0, GarminPositionMapper.renderedToOriginal(rendered, m), EPS);
    }

    private static boolean ffmpegAvailable() {
        try {
            return new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The rendered file's real duration must match what the manifest predicts —
     * i.e. the ffmpeg graph the plan builds and the manifest's bookkeeping agree.
     * Self-contained (synthesizes its source); skipped when ffmpeg is absent.
     */
    @Test
    public void renderedDurationMatchesManifestPrediction() throws Exception {
        assumeTrue("ffmpeg not available", ffmpegAvailable());

        // 40s tone source; drop [10,20] (10s) at 2.0x -> manifest predicts (40-10)/2 = 15s.
        File src = File.createTempFile("garmin-src", ".mp3");
        assertTrue(new GarminProcessFfmpegExecutor().run(Arrays.asList("ffmpeg", "-v", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=40", src.getAbsolutePath())));

        GarminAudioRenderPlan plan = GarminAudioRenderPlan.create("g", 2.0,
                Arrays.asList(new GarminRenderManifest.Range(10, 20)), 40.0);
        File out = File.createTempFile("garmin-out", ".mp3");
        GarminProcessFfmpegExecutor exec = new GarminProcessFfmpegExecutor();
        assertTrue("ffmpeg failed: " + exec.lastOutput(),
                exec.run(plan.ffmpegArgs("ffmpeg", src.getAbsolutePath(), out.getAbsolutePath())));

        double predicted = plan.manifest().renderedDurationSeconds(); // 15.0
        double actual = durationSeconds(out);
        assertEquals(15.0, predicted, EPS);
        assertTrue("predicted " + predicted + " vs actual " + actual, Math.abs(predicted - actual) < 1.0);

        //noinspection ResultOfMethodCallIgnored
        src.delete();
        //noinspection ResultOfMethodCallIgnored
        out.delete();
    }

    private double durationSeconds(File f) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-i", f.getAbsolutePath())
                .redirectErrorStream(true).start();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
        String line;
        double seconds = -1;
        while ((line = r.readLine()) != null) {
            int idx = line.indexOf("Duration:");
            if (idx >= 0) {
                String t = line.substring(idx + 9, line.indexOf(',', idx)).trim();
                String[] parts = t.split(":");
                seconds = Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Double.parseDouble(parts[2]);
            }
        }
        p.waitFor();
        return seconds;
    }
}
