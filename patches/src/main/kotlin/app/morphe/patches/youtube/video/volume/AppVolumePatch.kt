/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.volume

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val appVolumePatch = bytecodePatch(
    name = "App volume",
    description = "Adds a volume slider for videos that is independent of the device volume. " +
            "Long-press the Mute button in the player to open it.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // Applies the slider as its own volume channel, next to mute and ducking.
        playerVolumeHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            NonInteractivePreference(
                key = "morphe_app_volume",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                selectable = true
            ),
            SwitchPreference("morphe_app_volume_long_press")
        )
    }
}
