package de.danoeh.antennapod.garmin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A {@link GarminRenderer.FfmpegExecutor} that runs an ffmpeg binary via
 * {@link ProcessBuilder}. Pure Java (no Android deps) so it's usable wherever an
 * ffmpeg executable is on the path or bundled — desktop tooling, tests, or an
 * Android build that ships an ffmpeg binary.
 *
 * <p>On a stock phone there's no ffmpeg binary; provide a library-backed
 * {@code FfmpegExecutor} there instead. This impl keeps the orchestration testable
 * end-to-end (plan → args → ffmpeg → file) without a device.
 */
public class GarminProcessFfmpegExecutor implements GarminFfmpegExecutor {

    private final StringBuilder lastOutput = new StringBuilder();

    @Override
    public boolean run(List<String> args) {
        lastOutput.setLength(0);
        try {
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastOutput.append(line).append('\n');
                }
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            lastOutput.append(e).append('\n');
            return false;
        }
    }

    /** ffmpeg's combined stdout/stderr from the last run (for diagnostics). */
    public String lastOutput() {
        return lastOutput.toString();
    }
}
