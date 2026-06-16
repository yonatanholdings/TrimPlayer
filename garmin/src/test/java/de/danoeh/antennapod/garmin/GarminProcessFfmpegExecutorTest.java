package de.danoeh.antennapod.garmin;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * End-to-end render test through real ffmpeg: plan -> args -> executor -> file.
 * Self-contained — it synthesizes its own source audio with ffmpeg's lavfi, so it
 * needs no fixture file. Skipped automatically (Assume) when no ffmpeg binary is
 * available, so it never breaks CI on machines without ffmpeg.
 */
public class GarminProcessFfmpegExecutorTest {

    private static boolean ffmpegAvailable() {
        try {
            return new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Make a 30s tone so we can verify trimming + speed shorten it. */
    private File synthSource() throws Exception {
        File src = File.createTempFile("garmin-src", ".mp3");
        List<String> args = Arrays.asList("ffmpeg", "-v", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=30",
                src.getAbsolutePath());
        assertTrue(new GarminProcessFfmpegExecutor().run(args));
        return src;
    }

    private double durationSeconds(File f) throws Exception {
        // Parse "Duration: HH:MM:SS.xx" from ffmpeg -i.
        Process p = new ProcessBuilder("ffmpeg", "-i", f.getAbsolutePath())
                .redirectErrorStream(true).start();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
        String line;
        double seconds = -1;
        while ((line = r.readLine()) != null) {
            int idx = line.indexOf("Duration:");
            if (idx >= 0) {
                String t = line.substring(idx + 9).trim();
                t = t.substring(0, t.indexOf(',')).trim();
                String[] parts = t.split(":");
                seconds = Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Double.parseDouble(parts[2]);
            }
        }
        p.waitFor();
        return seconds;
    }

    @Test
    public void rendersTrimmedAndSpedFileThroughRealFfmpeg() throws Exception {
        assumeTrue("ffmpeg not available", ffmpegAvailable());

        File src = synthSource();
        File out = File.createTempFile("garmin-out", ".mp3");

        // Drop [5,10] (5s) from a 30s source, then 1.25x.
        // Expected: (30 - 5) / 1.25 = 20s.
        GarminAudioRenderPlan plan = GarminAudioRenderPlan.create("g", 1.25,
                Collections.singletonList(new GarminRenderManifest.Range(5, 10)), 30.0);
        List<String> args = plan.ffmpegArgs("ffmpeg", src.getAbsolutePath(), out.getAbsolutePath());

        GarminProcessFfmpegExecutor exec = new GarminProcessFfmpegExecutor();
        assertTrue("ffmpeg failed: " + exec.lastOutput(), exec.run(args));
        assertTrue(out.length() > 0);

        double rendered = durationSeconds(out);
        // Allow slack for mp3 frame padding.
        assertTrue("expected ~20s, got " + rendered, Math.abs(rendered - 20.0) < 1.0);
        // And it matches what the manifest predicts.
        assertTrue(Math.abs(rendered - plan.manifest().renderedDurationSeconds()) < 1.0);

        //noinspection ResultOfMethodCallIgnored
        src.delete();
        //noinspection ResultOfMethodCallIgnored
        out.delete();
    }
}
