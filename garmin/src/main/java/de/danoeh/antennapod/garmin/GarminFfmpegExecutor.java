package de.danoeh.antennapod.garmin;

import java.util.List;

/**
 * Runs an ffmpeg invocation. Abstracted so the module doesn't hard-depend on a
 * particular ffmpeg library: ffmpeg-kit is retired, so an Android build supplies a
 * maintained-fork or MediaCodec+SoundTouch implementation, while
 * {@link GarminProcessFfmpegExecutor} shells out to an ffmpeg binary for desktop
 * tooling and tests. Pure Java (no Android deps).
 */
public interface GarminFfmpegExecutor {
    /**
     * @return true on success (exit 0).
     */
    boolean run(List<String> args);
}
