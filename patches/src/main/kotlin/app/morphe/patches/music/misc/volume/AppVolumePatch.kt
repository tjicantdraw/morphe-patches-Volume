/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.volume

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/AppVolumePatch;"

private const val VOLUME_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/AppVolumePatch$VolumeAccess;"

/** Player.EVENT_VOLUME_CHANGED, sent by ExoPlayerImpl.setVolume(float). */
private const val EVENT_VOLUME_CHANGED = 22

@Suppress("unused")
val appVolumePatch = bytecodePatch(
    name = "App volume",
    description = "Adds a volume slider for YouTube Music that is independent of the device volume.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // Used to apply a changed volume setting while a track is playing.
        musicVideoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            noTitleUnsortedPreferenceCategory(
                NonInteractivePreference(
                    key = "morphe_music_app_volume",
                    tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                    selectable = true
                )
            )
        )

        val exoPlayerImplClass = VolumeExoPlayerImplFingerprint.classDef

        // Names of the (F)V methods on the media3 Player interface (setVolume, setPlaybackSpeed).
        val playerInterfaceType = classDefBy(EXO_PLAYER_TYPE).interfaces.first()
        val playerFloatSetterNames = classDefBy(playerInterfaceType).methods
            .filter { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("F") }
            .map { it.name }
            .toSet()

        fun MutableMethod.sendsVolumeChangedEvent() = implementation != null && instructions.any {
            (it as? NarrowLiteralInstruction)?.narrowLiteral == EVENT_VOLUME_CHANGED
        }

        // setPlaybackSpeed lives on BasePlayer, so the impl class itself
        // only declares setVolume. The event literal settles any doubt.
        val candidates = exoPlayerImplClass.methods.filter { method ->
            method.returnType == "V"
                    && method.parameterTypes.map(CharSequence::toString) == listOf("F")
                    && method.implementation != null
                    && !AccessFlags.STATIC.isSet(method.accessFlags)
                    && method.name in playerFloatSetterNames
        }
        val setVolumeMethod = candidates.singleOrNull()
            ?: candidates.singleOrNull { it.sendsVolumeChangedEvent() }
            ?: throw IllegalStateException(
                "setVolume not found in ${exoPlayerImplClass.type}, candidates: ${candidates.map { it.name }}"
            )

        // Every volume the app (or the Crossfade patch) sets passes through here.
        // The extension keeps the requested volume and returns it scaled by the slider.
        setVolumeMethod.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->overrideVolume(Ljava/lang/Object;F)F
                move-result p1
            """
        )

        // Register each player when it is created, since the app may never call setVolume itself.
        // More than one player can be alive at the same time, such as when crossfading.
        // The constructor uses many registers, so p0 can be above v15, which plain invoke-static
        // cannot address. The range form can. addInstructions compiles with the method's
        // parameters and register count, so p0 resolves to the right register.
        exoPlayerImplClass.methods.filter { it.name == "<init>" }.forEach { constructor ->
            constructor.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                constructor.addInstructions(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->registerPlayer(Ljava/lang/Object;)V"
                )
            }
        }

        // Let the extension re-send the requested volume when the slider changes.
        val setVolumeReference = "${exoPlayerImplClass.type}->${setVolumeMethod.name}(F)V"
        exoPlayerImplClass.interfaces.add(VOLUME_ACCESS_INTERFACE)
        exoPlayerImplClass.methods.add(
            ImmutableMethod(
                exoPlayerImplClass.type,
                "patch_appVolume_setVolume",
                listOf(ImmutableMethodParameter("F", null, null)),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0, p1 }, $setVolumeReference
                        return-void
                    """
                )
            }
        )

        // Called on the main thread about every second while a track is playing.
        musicVideoTimeHook(EXTENSION_CLASS, "setVideoTime")
    }
}
