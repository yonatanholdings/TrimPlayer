package de.danoeh.antennapod.garmin;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;

/**
 * Renders an episode for the Garmin watch (trim + pitch-preserved speed) and records
 * the {@link GarminRenderManifest} so the watch's reported position can later be
 * mapped back to original time.
 *
 * <p>The render graph itself is built and verified by {@link GarminAudioRenderPlan}
 * (its ffmpeg graph is byte-for-byte identical to the backend/stub). This class is
 * just the orchestration: pick a cache path, run ffmpeg, persist the manifest. The
 * actual ffmpeg invocation is delegated to a pluggable {@link GarminFfmpegExecutor}
 * so the module doesn't hard-depend on a particular ffmpeg library (ffmpeg-kit is
 * retired; a maintained fork or MediaCodec+SoundTouch can implement that interface).
 */
public class GarminRenderer {

    private static final String TAG = "GarminRenderer";

    private final Context context;
    private final GarminRenderManifestStore manifests;
    private final GarminFfmpegExecutor ffmpeg;
    private final String ffmpegBin;

    public GarminRenderer(Context context, GarminFfmpegExecutor ffmpeg) {
        this(context, ffmpeg, "ffmpeg");
    }

    public GarminRenderer(Context context, GarminFfmpegExecutor ffmpeg, String ffmpegBin) {
        this.context = context.getApplicationContext();
        this.manifests = new GarminRenderManifestStore(this.context);
        this.ffmpeg = ffmpeg;
        this.ffmpegBin = ffmpegBin;
    }

    /**
     * Render an episode to a trimmed + speed-adjusted file in the Garmin cache dir,
     * persisting its manifest on success.
     *
     * @param guid            PortCast episode guid
     * @param speed           playback rate (pitch preserved)
     * @param skipped         ranges to drop, in original seconds (from TrimClient segments)
     * @param durationSeconds source episode duration
     * @param sourcePath      the downloaded original audio
     * @return the rendered file, or {@code null} if rendering failed
     */
    @Nullable
    public File render(String guid, double speed, List<GarminRenderManifest.Range> skipped,
                       double durationSeconds, File sourcePath) {
        GarminAudioRenderPlan plan = GarminAudioRenderPlan.create(guid, speed, skipped, durationSeconds);

        File outDir = new File(context.getCacheDir(), "garmin");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        File out = new File(outDir, plan.outputFileName());

        if (out.exists() && out.length() > 0) {
            // Already rendered for this (guid, speed) — make sure the manifest is present.
            if (manifests.get(guid) == null) {
                manifests.put(plan.manifest());
            }
            return out;
        }

        List<String> args = plan.ffmpegArgs(ffmpegBin, sourcePath.getAbsolutePath(), out.getAbsolutePath());
        boolean ok = ffmpeg.run(args);
        if (!ok) {
            Log.e(TAG, "ffmpeg render failed for guid=" + guid);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        }

        // Persist the manifest so GarminPortcastBridge can invert reported positions.
        manifests.put(plan.manifest());
        return out;
    }
}
