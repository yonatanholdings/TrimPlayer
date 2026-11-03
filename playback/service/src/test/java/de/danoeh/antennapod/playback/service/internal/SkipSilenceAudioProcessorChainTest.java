package de.danoeh.antennapod.playback.service.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Guards {@link SkipSilenceAudioProcessorChain}: with skip-silence on, an episode ending in quiet
 * audio shorter than the minimum silence never reached STATE_ENDED (feedback #20), because media3
 * 1.1.1 re-queues end-of-stream on every pipeline pass and the silence processor re-emits its
 * buffered tail each time.
 */
public class SkipSilenceAudioProcessorChainTest {

    private static final AudioProcessor.AudioFormat MONO_16 =
            new AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT);

    @Test
    public void rawProcessorReEmitsTailOnRepeatedEndOfStream() throws Exception {
        // Documents the media3 1.1.1 behaviour the wrapper exists for; if this starts failing
        // after a media3 upgrade, the upstream fix is in and the wrapper can go.
        SilenceSkippingAudioProcessor raw = enabledProcessor();
        AudioProcessor p = configured(raw);
        feedNoiseThenShortQuietTail(p);
        p.queueEndOfStream();
        int first = drain(p);
        p.queueEndOfStream();
        int second = drain(p);
        assertTrue("tail is emitted at end of stream", first > 0);
        assertEquals("1.1.1 emits the same tail again", first, second);
    }

    @Test
    public void repeatedEndOfStreamEmitsTailOnlyOnce() throws Exception {
        AudioProcessor p = configured(
                new SkipSilenceAudioProcessorChain.EndOfStreamOnceProcessor(enabledProcessor()));
        feedNoiseThenShortQuietTail(p);
        p.queueEndOfStream();
        int first = drain(p);
        assertTrue(first > 0);
        for (int i = 0; i < 5; i++) {
            p.queueEndOfStream();
            assertEquals(0, drain(p));
        }
        assertTrue(p.isEnded());
    }

    @Test
    public void flushReArmsEndOfStream() throws Exception {
        AudioProcessor p = configured(
                new SkipSilenceAudioProcessorChain.EndOfStreamOnceProcessor(enabledProcessor()));
        feedNoiseThenShortQuietTail(p);
        p.queueEndOfStream();
        drain(p);
        p.flush(); // seek
        feedNoiseThenShortQuietTail(p);
        p.queueEndOfStream();
        assertTrue("end of stream after a seek still drains the tail", drain(p) > 0);
        assertTrue(p.isEnded());
    }

    private static SilenceSkippingAudioProcessor enabledProcessor() {
        SilenceSkippingAudioProcessor p = new SilenceSkippingAudioProcessor();
        p.setEnabled(true);
        return p;
    }

    private static AudioProcessor configured(AudioProcessor p) throws Exception {
        p.configure(MONO_16);
        p.flush();
        return p;
    }

    /** 200 ms of loud audio, then 50 ms of silence: less than the 150 ms minimum silence. */
    private static void feedNoiseThenShortQuietTail(AudioProcessor p) {
        int noisy = 44_100 / 5;
        int quiet = 44_100 / 20;
        ByteBuffer in = ByteBuffer.allocateDirect((noisy + quiet) * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < noisy; i++) {
            in.putShort((short) (i % 2 == 0 ? 8000 : -8000));
        }
        for (int i = 0; i < quiet; i++) {
            in.putShort((short) 0);
        }
        in.flip();
        while (in.hasRemaining()) {
            p.queueInput(in);
            drain(p);
        }
    }

    private static int drain(AudioProcessor p) {
        int total = 0;
        ByteBuffer out = p.getOutput();
        while (out.hasRemaining()) {
            total += out.remaining();
            out.position(out.limit());
            out = p.getOutput();
        }
        return total;
    }
}
