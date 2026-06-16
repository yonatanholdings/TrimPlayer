package de.danoeh.antennapod.garmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Verifies the ffmpeg filter graph + manifest the phone render produces match the
 * reference (backend / stub) render, so phone- and server-rendered files behave
 * identically.
 */
public class GarminAudioRenderPlanTest {

    private GarminAudioRenderPlan episode1() {
        List<GarminRenderManifest.Range> skipped = Arrays.asList(
                new GarminRenderManifest.Range(0, 20),
                new GarminRenderManifest.Range(60, 75));
        return GarminAudioRenderPlan.create("guid-1", 1.5, skipped, 156.03);
    }

    @Test
    public void filterGraphMatchesReference() {
        // Same graph shape the stub/backend emit: atrim kept ranges -> concat -> atempo.
        String expected =
                "[0:a]atrim=start=20:end=60,asetpts=PTS-STARTPTS[a0];"
              + "[0:a]atrim=start=75:end=156.03,asetpts=PTS-STARTPTS[a1];"
              + "[a0][a1]concat=n=2:v=0:a=1[ac];"
              + "[ac]atempo=1.500000[out]";
        assertEquals(expected, episode1().filterComplex());
    }

    @Test
    public void atempoChainsAboveTwo() {
        assertEquals("atempo=2.000000,atempo=1.500000", GarminAudioRenderPlan.atempoChain(3.0));
    }

    @Test
    public void atempoSingleInRange() {
        assertEquals("atempo=1.200000", GarminAudioRenderPlan.atempoChain(1.2));
    }

    @Test
    public void manifestMatchesPlan() {
        GarminRenderManifest m = episode1().manifest();
        assertEquals(1.5, m.speed, 1e-9);
        assertEquals(2, m.keptRanges.size());
        // 40 + 81.03 = 121.03 concat; /1.5 = 80.6867 rendered — matches observed 1:20.69
        assertEquals(80.686667, m.renderedDurationSeconds(), 1e-4);
    }

    @Test
    public void noSkipsSingleFullRange() {
        GarminAudioRenderPlan plan = GarminAudioRenderPlan.create("g", 1.0,
                Collections.emptyList(), 100.0);
        assertEquals(
                "[0:a]atrim=start=0:end=100,asetpts=PTS-STARTPTS[a0];"
              + "[a0]concat=n=1:v=0:a=1[ac];"
              + "[ac]atempo=1.000000[out]",
                plan.filterComplex());
    }

    @Test
    public void ffmpegArgsAreWellFormed() {
        List<String> args = episode1().ffmpegArgs("ffmpeg", "in.mp3", "out.mp3");
        assertEquals("ffmpeg", args.get(0));
        assertTrue(args.contains("-filter_complex"));
        assertTrue(args.contains("[out]"));
        assertEquals("out.mp3", args.get(args.size() - 1));
    }

    @Test
    public void speedIsClamped() {
        assertEquals(3.0, GarminAudioRenderPlan.create("g", 9.0,
                Collections.emptyList(), 10.0).manifest().speed, 1e-9);
    }
}
