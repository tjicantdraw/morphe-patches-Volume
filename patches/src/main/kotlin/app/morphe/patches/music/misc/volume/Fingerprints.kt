/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.volume

import app.morphe.patcher.Fingerprint

internal const val EXO_PLAYER_TYPE = "Landroidx/media3/exoplayer/ExoPlayer;"

/**
 * The ExoPlayer implementation class. A synthetic Runnable also logs with the
 * "ExoPlayerImpl" tag, so the interface check is required.
 */
internal object VolumeExoPlayerImplFingerprint : Fingerprint(
    interfaces = listOf(EXO_PLAYER_TYPE),
    strings = listOf("ExoPlayerImpl")
)
