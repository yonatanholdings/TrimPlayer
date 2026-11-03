package de.danoeh.antennapod.playback.service.internal;

import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessorChain;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor;

import java.nio.ByteBuffer;

/**
 * media3's default silence-skipping + speed chain, with the silence processor guarded against
 * repeated end-of-stream signals.
 *
 * <p>In media3 1.1.1 {@code AudioProcessingPipeline} calls {@code queueEndOfStream()} on a processor
 * again on every pass once the processor before it has ended, and
 * {@link SilenceSkippingAudioProcessor} re-emits its buffered "maybe silent" tail on each call
 * (it never clears that buffer). When an episode ends in quiet audio shorter than the minimum
 * silence, the sink is fed the same near-silent chunk forever: the position keeps advancing past
 * the duration and {@code STATE_ENDED} never arrives (feedback #20: Omny episode, skip-silence on,
 * playhead 79 s past the end, next episode never started; identical file ends normally with
 * skip-silence off). Upstream fix: androidx/media#712, released in 1.2.0. Drop this class once the
 * app is on media3 1.2.0 or later.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SkipSilenceAudioProcessorChain implements AudioProcessorChain {

    private final SilenceSkippingAudioProcessor silenceSkipping = new SilenceSkippingAudioProcessor();
    private final SonicAudioProcessor sonic = new SonicAudioProcessor();
    private final AudioProcessor[] audioProcessors = {
            new EndOfStreamOnceProcessor(silenceSkipping), sonic
    };

    @Override
    public AudioProcessor[] getAudioProcessors() {
        return audioProcessors;
    }

    @Override
    public PlaybackParameters applyPlaybackParameters(PlaybackParameters playbackParameters) {
        sonic.setSpeed(playbackParameters.speed);
        sonic.setPitch(playbackParameters.pitch);
        return playbackParameters;
    }

    @Override
    public boolean applySkipSilenceEnabled(boolean skipSilenceEnabled) {
        silenceSkipping.setEnabled(skipSilenceEnabled);
        return skipSilenceEnabled;
    }

    @Override
    public long getMediaDuration(long playoutDuration) {
        return sonic.getMediaDuration(playoutDuration);
    }

    @Override
    public long getSkippedOutputFrameCount() {
        return silenceSkipping.getSkippedFrames();
    }

    /** Forwards everything to {@code delegate}, but only the first end-of-stream per flush. */
    static final class EndOfStreamOnceProcessor implements AudioProcessor {
        private final AudioProcessor delegate;
        private boolean endOfStreamQueued;

        EndOfStreamOnceProcessor(AudioProcessor delegate) {
            this.delegate = delegate;
        }

        @Override
        public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
            return delegate.configure(inputAudioFormat);
        }

        @Override
        public boolean isActive() {
            return delegate.isActive();
        }

        @Override
        public void queueInput(ByteBuffer inputBuffer) {
            delegate.queueInput(inputBuffer);
        }

        @Override
        public void queueEndOfStream() {
            if (endOfStreamQueued) {
                return;
            }
            endOfStreamQueued = true;
            delegate.queueEndOfStream();
        }

        @Override
        public ByteBuffer getOutput() {
            return delegate.getOutput();
        }

        @Override
        public boolean isEnded() {
            return delegate.isEnded();
        }

        @Override
        public void flush() {
            endOfStreamQueued = false;
            delegate.flush();
        }

        @Override
        public void reset() {
            endOfStreamQueued = false;
            delegate.reset();
        }
    }
}
