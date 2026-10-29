/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;

/**
 * Scales the volume of YouTube Music's own players, independent of the device volume.
 * The slider value is mapped on a squared curve, so the lower half of the slider
 * gives fine control over quiet levels, which a linear slider would rush through.
 */
@SuppressWarnings("unused")
public class AppVolumePatch {

    /**
     * Implemented by the ExoPlayer implementation class during patching.
     */
    public interface VolumeAccess {
        /** Calls the player's own setVolume, which passes through {@link #overrideVolume}. */
        void patch_appVolume_setVolume(float volume);
    }

    /** Index of the volume the app last requested. */
    private static final int REQUESTED = 0;
    /** Index of the gain last applied, NaN if none yet. */
    private static final int APPLIED_GAIN = 1;

    /**
     * Requested volume and applied gain for each live player.
     * More than one player can be alive at the same time, such as when crossfading.
     */
    private static final Map<VolumeAccess, float[]> players =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static float getGain() {
        final float level = SeekBarPreference.clampToRange(Settings.APP_VOLUME) / 100f;
        return level * level;
    }

    /**
     * Injection point.
     * End of each ExoPlayerImpl constructor. A new player starts at full volume.
     */
    public static void registerPlayer(Object player) {
        try {
            if (player instanceof VolumeAccess access) {
                players.put(access, new float[]{1f, Float.NaN});
            }
        } catch (Exception ex) {
            Logger.printException(() -> "registerPlayer failure", ex);
        }
    }

    /**
     * Injection point.
     * Start of ExoPlayerImpl.setVolume(float).
     *
     * @param volume Volume the app is setting, 0 to 1.
     * @return The volume to actually use.
     */
    public static float overrideVolume(Object player, float volume) {
        try {
            final float gain = getGain();
            if (player instanceof VolumeAccess access) {
                players.put(access, new float[]{volume, gain});
            }
            return volume * gain;
        } catch (Exception ex) {
            Logger.printException(() -> "overrideVolume failure", ex);
            return volume;
        }
    }

    /**
     * Injection point.
     * Called on the main thread about every second while a track is playing.
     * Applies a changed slider value, and the slider value to new players.
     */
    public static void setVideoTime(long time) {
        try {
            final float gain = getGain();

            List<Map.Entry<VolumeAccess, float[]>> entries;
            synchronized (players) {
                entries = new ArrayList<>(players.entrySet());
            }

            for (Map.Entry<VolumeAccess, float[]> entry : entries) {
                VolumeAccess player = entry.getKey();
                float[] state = entry.getValue();
                if (player == null || state[APPLIED_GAIN] == gain) {
                    continue;
                }

                // Set before applying, so a failure is not retried every second.
                state[APPLIED_GAIN] = gain;

                final float requested = state[REQUESTED];
                Logger.printDebug(() -> "Applying app volume gain: " + gain + " to volume: " + requested);
                player.patch_appVolume_setVolume(requested);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoTime failure", ex);
        }
    }
}
