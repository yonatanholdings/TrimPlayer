package de.danoeh.antennapod.garmin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plans the on-device render of an episode for the Garmin watch: from the detected
 * skip ranges + the user's playback speed it produces (a) the ffmpeg filter graph
 * that drops the skipped ranges and applies a pitch-preserved tempo change, and
 * (b) the {@link GarminRenderManifest} needed later to map the watch's reported
 * position back to original time.
 *
 * <p>The filter graph mirrors the backend (`app/garmin.py`) and the watch repo's
 * stub server byte-for-byte, so phone-rendered and server-rendered files behave
 * identically. Pure Java — the graph/manifest construction is unit-tested; only the
 * actual ffmpeg invocation (see GarminRenderer in the module README) needs a device.
 */
public final class GarminAudioRenderPlan {

    private final String guid;
    private final double speed;
    private final double durationSeconds;
    private final List<GarminRenderManifest.Range> kept;

    private GarminAudioRenderPlan(String guid, double speed, double durationSeconds,
                                  List<GarminRenderManifest.Range> kept) {
        this.guid = guid;
        this.speed = speed;
        this.durationSeconds = durationSeconds;
        this.kept = kept;
    }

    /**
     * @param guid            PortCast episode guid
     * @param speed           playback rate (pitch preserved); clamped to [0.5, 3.0]
     * @param skipped         ranges to drop, in original seconds
     * @param durationSeconds source episode duration
     */
    public static GarminAudioRenderPlan create(String guid, double speed,
                                               List<GarminRenderManifest.Range> skipped,
                                               double durationSeconds) {
        double s = Math.max(0.5, Math.min(3.0, speed <= 0 ? 1.0 : speed));
        GarminRenderManifest manifest =
                GarminRenderManifest.fromSkipped(guid, s, skipped, durationSeconds);
        return new GarminAudioRenderPlan(guid, s, durationSeconds, manifest.keptRanges);
    }

    public GarminRenderManifest manifest() {
        return new GarminRenderManifest(guid, speed, kept);
    }

    /** Deterministic output filename for caching by (guid, speed). */
    public String outputFileName() {
        return "garmin_" + guid.hashCode() + "_" + String.format(Locale.US, "%.2f", speed) + ".mp3";
    }

    /**
     * The ffmpeg {@code -filter_complex} graph: atrim each kept range, concat, then
     * a pitch-preserved atempo chain.
     */
    public String filterComplex() {
        List<String> chains = new ArrayList<>();
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            GarminRenderManifest.Range r = kept.get(i);
            chains.add(String.format(Locale.US,
                    "[0:a]atrim=start=%s:end=%s,asetpts=PTS-STARTPTS[a%d]",
                    trimNum(r.startSeconds), trimNum(r.endSeconds), i));
            labels.append("[a").append(i).append("]");
        }
        String concat = labels + "concat=n=" + kept.size() + ":v=0:a=1[ac]";
        String tempo = "[ac]" + atempoChain(speed) + "[out]";

        StringBuilder sb = new StringBuilder();
        for (String c : chains) {
            sb.append(c).append(';');
        }
        sb.append(concat).append(';').append(tempo);
        return sb.toString();
    }

    /** Full ffmpeg argument list for the render. */
    public List<String> ffmpegArgs(String ffmpegBin, String inputPath, String outputPath) {
        List<String> args = new ArrayList<>();
        args.add(ffmpegBin);
        args.add("-v");
        args.add("error");
        args.add("-y");
        args.add("-i");
        args.add(inputPath);
        args.add("-filter_complex");
        args.add(filterComplex());
        args.add("-map");
        args.add("[out]");
        args.add(outputPath);
        return args;
    }

    /** ffmpeg atempo accepts 0.5–2.0 per instance; chain for anything outside. */
    static String atempoChain(double speed) {
        List<Double> parts = new ArrayList<>();
        double s = speed;
        while (s > 2.0) {
            parts.add(2.0);
            s /= 2.0;
        }
        while (s < 0.5) {
            parts.add(0.5);
            s /= 0.5;
        }
        parts.add(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "atempo=%.6f", parts.get(i)));
        }
        return sb.toString();
    }

    private static String trimNum(double v) {
        // Whole numbers without a trailing ".0" to match the reference graphs.
        if (v == Math.rint(v)) {
            return Long.toString((long) v);
        }
        return String.format(Locale.US, "%s", v);
    }
}
