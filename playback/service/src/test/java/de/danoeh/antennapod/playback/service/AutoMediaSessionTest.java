package de.danoeh.antennapod.playback.service;

import de.danoeh.antennapod.playback.service.internal.AutoMediaSessionUtils;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AutoMediaSessionTest {

    // -------------------------------------------------------------------------
    // formatSpeedLabel
    // -------------------------------------------------------------------------

    @Test
    public void formatSpeedLabel_integer_omitsDecimal() {
        assertEquals("1×", AutoMediaSessionUtils.formatSpeedLabel(1.0f));
        assertEquals("2×", AutoMediaSessionUtils.formatSpeedLabel(2.0f));
    }

    @Test
    public void formatSpeedLabel_oneDecimalPlace() {
        assertEquals("1.5×", AutoMediaSessionUtils.formatSpeedLabel(1.5f));
        assertEquals("0.5×", AutoMediaSessionUtils.formatSpeedLabel(0.5f));
    }

    @Test
    public void formatSpeedLabel_twoDecimalPlaces_trailingZeroStripped() {
        assertEquals("0.75×", AutoMediaSessionUtils.formatSpeedLabel(0.75f));
        assertEquals("1.25×", AutoMediaSessionUtils.formatSpeedLabel(1.25f));
    }

    // -------------------------------------------------------------------------
    // nextSpeedPreset
    // -------------------------------------------------------------------------

    @Test
    public void nextSpeedPreset_advancesToNext() {
        List<Float> presets = Arrays.asList(0.75f, 1.0f, 1.5f, 2.0f);
        assertEquals(1.5f, AutoMediaSessionUtils.nextSpeedPreset(1.0f, presets), 0.001f);
    }

    @Test
    public void nextSpeedPreset_wrapsAtEnd() {
        List<Float> presets = Arrays.asList(0.75f, 1.0f, 1.5f, 2.0f);
        assertEquals(0.75f, AutoMediaSessionUtils.nextSpeedPreset(2.0f, presets), 0.001f);
    }

    @Test
    public void nextSpeedPreset_returnsFirstWhenCurrentNotInList() {
        // Speed not in presets (e.g. feed-specific 1.3×): snaps to first preset
        List<Float> presets = Arrays.asList(0.75f, 1.0f, 1.5f);
        assertEquals(0.75f, AutoMediaSessionUtils.nextSpeedPreset(1.3f, presets), 0.001f);
    }

    @Test
    public void nextSpeedPreset_singleElement_wrapsToSelf() {
        List<Float> presets = Collections.singletonList(1.5f);
        assertEquals(1.5f, AutoMediaSessionUtils.nextSpeedPreset(1.5f, presets), 0.001f);
    }

    @Test
    public void nextSpeedPreset_firstElement_advancesToSecond() {
        List<Float> presets = Arrays.asList(1.0f, 1.5f, 2.0f);
        assertEquals(1.5f, AutoMediaSessionUtils.nextSpeedPreset(1.0f, presets), 0.001f);
    }
}
