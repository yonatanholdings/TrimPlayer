package de.danoeh.antennapod.playback.service.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;

/**
 * Guards {@link ExoPlayerWrapper#patientLoadErrorHandlingPolicy()} — the streaming load-error
 * policy that keeps transient network drops from escalating to a fatal player error.
 *
 * <p>media3's default gives up after ~3 retries with a ≤5s backoff, so a brief cellular stall
 * stops playback even when audio is buffered ahead. This policy retries far longer with a capped
 * exponential backoff (so playback rides through from buffer) while still failing fast on genuinely
 * permanent faults so a dead stream doesn't loop forever.
 */
@OptIn(markerClass = UnstableApi.class)
public class ExoPlayerWrapperLoadErrorPolicyTest {

    private final LoadErrorHandlingPolicy policy = ExoPlayerWrapper.patientLoadErrorHandlingPolicy();

    /** Only {@code exception} and {@code errorCount} are read by the policy, so the event/load
     *  metadata can be null. */
    private static LoadErrorHandlingPolicy.LoadErrorInfo errorInfo(IOException ex, int errorCount) {
        return new LoadErrorHandlingPolicy.LoadErrorInfo(
                /* loadEventInfo= */ null, /* mediaLoadData= */ null, ex, errorCount);
    }

    @Test
    public void retriesFarMoreThanTheMediaDefault() {
        // media3's default is 3; we need to stay patient through multi-second drops.
        assertEquals(30, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA));
    }

    @Test
    public void transientNetworkErrorBacksOffExponentially() {
        IOException networkError = new IOException("connection reset");
        assertEquals(1000L, policy.getRetryDelayMsFor(errorInfo(networkError, 1)));
        assertEquals(2000L, policy.getRetryDelayMsFor(errorInfo(networkError, 2)));
        assertEquals(4000L, policy.getRetryDelayMsFor(errorInfo(networkError, 3)));
        // errorCount 4 → 1000<<3 = 8000, clamped to the 5s ceiling so a restored connection is
        // re-attempted within ~5s instead of sitting idle (the loader's retries can't be triggered
        // by our connectivity callback, so the tight cap is the lever).
        assertEquals(5000L, policy.getRetryDelayMsFor(errorInfo(networkError, 4)));
        assertEquals(5000L, policy.getRetryDelayMsFor(errorInfo(networkError, 5)));
    }

    @Test
    public void transientBackoffIsCappedAtFiveSeconds() {
        IOException networkError = new IOException("read timed out");
        // errorCount 6 → 1000<<5 = 32000, clamped to the 5s ceiling, and stays there.
        assertEquals(5000L, policy.getRetryDelayMsFor(errorInfo(networkError, 6)));
        assertEquals(5000L, policy.getRetryDelayMsFor(errorInfo(networkError, 50)));
    }

    @Test
    public void permanentFaultIsNotRetried() {
        // A parse error is unrecoverable; retrying would just fail again. The policy must defer to
        // the default's verdict and surface it as fatal (C.TIME_UNSET) instead of looping.
        ParserException parseError =
                ParserException.createForMalformedContainer("malformed", /* cause= */ null);
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(parseError, 1)));
    }

    private static HttpDataSource.InvalidResponseCodeException httpError(int code) {
        return new HttpDataSource.InvalidResponseCodeException(code, null, null,
                Collections.emptyMap(), new DataSpec(org.mockito.Mockito.mock(Uri.class)), new byte[0]);
    }

    @Test
    public void missingFileIsNotRetried() {
        // A 404 never fixes itself. Retried like a network drop it buffered silently for ~2.5
        // minutes in the car (2026-11-19) before the player gave up.
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(httpError(404), 1)));
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(httpError(410), 1)));
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(httpError(403), 1)));
    }

    @Test
    public void transientHttpStatusIsRetried() {
        assertEquals(1000L, policy.getRetryDelayMsFor(errorInfo(httpError(503), 1)));
        assertEquals(1000L, policy.getRetryDelayMsFor(errorInfo(httpError(429), 1)));
        assertEquals(1000L, policy.getRetryDelayMsFor(errorInfo(httpError(408), 1)));
    }

    @Test
    public void onlyNotFoundAndGoneCountAsSourceGone() {
        assertTrue(ExoPlayerWrapper.isSourceGone(httpError(404)));
        assertTrue(ExoPlayerWrapper.isSourceGone(new IOException("wrapped", httpError(410))));
        assertFalse(ExoPlayerWrapper.isSourceGone(httpError(403)));
        assertFalse(ExoPlayerWrapper.isSourceGone(httpError(503)));
        assertFalse(ExoPlayerWrapper.isSourceGone(new IOException("connection reset")));
    }
}
