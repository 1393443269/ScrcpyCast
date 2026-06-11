package com.scrcpycast.receiver

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Auto-detects chip platform and selects the best H.264 decoder.
 *
 * Priority: hardware → Codec2 → software fallback.
 * Known-bad decoder/version combos are blacklisted per platform.
 */
object DecoderSelector {

    private const val TAG = "DecoderSelector"

    enum class Chip {
        AMLOGIC,
        ALLWINNER,
        ROCKCHIP,
        QUALCOMM,
        OTHER
    }

    data class DecoderInfo(
        val name: String,
        val priority: Int,
        val notes: String = ""
    )

    // ── Platform detection ──────────────────────────────────────────

    val chip: Chip by lazy { detectChip() }
    val platform: String by lazy { getprop("ro.board.platform") }
    val hardware: String by lazy { getprop("ro.hardware") }
    val sdkInt: Int get() = Build.VERSION.SDK_INT

    private fun detectChip(): Chip {
        val p = platform.lowercase()
        val h = hardware.lowercase()
        return when {
            p.contains("meson") || h.contains("amlogic") || p.contains("s9") -> Chip.AMLOGIC
            p.contains("sun") || h.contains("allwinner") || p.contains("apollo") -> Chip.ALLWINNER
            p.contains("rk") || h.contains("rockchip") -> Chip.ROCKCHIP
            h.contains("qcom") || p.contains("msm") || p.contains("sdm") -> Chip.QUALCOMM
            else -> Chip.OTHER
        }
    }

    private fun getprop(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            process.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "" }
    }

    // ── Blacklist: known-bad (platform, decoder contains) ────────────

    data class BlacklistEntry(
        val chip: Chip,
        val decoderHint: String,
        val minSdk: Int? = null,
        val maxSdk: Int? = null,
        val reason: String = ""
    )

    private val blacklist = listOf(
        // Allwinner Cedar crashes on Android 9-10, unstable on ≥12
        BlacklistEntry(Chip.ALLWINNER, "allwinner",
            maxSdk = 30, reason = "Cedar crashes on Android 9-10"),
        // Rockchip OMX unstable via Surface
        BlacklistEntry(Chip.ROCKCHIP, "rockchip.avc.decoder",
            reason = "Rockchip OMX unstable via Surface"),
    )

    private fun isBlacklisted(name: String): Boolean {
        val lower = name.lowercase()
        return blacklist.any { entry ->
            if (entry.chip != chip) return@any false
            if (!lower.contains(entry.decoderHint.lowercase())) return@any false
            if (entry.minSdk != null && sdkInt < entry.minSdk) return@any false
            if (entry.maxSdk != null && sdkInt > entry.maxSdk) return@any false
            true
        }
    }

    // ── Decoder priority ─────────────────────────────────────────────

    private fun priority(name: String): Int {
        val lower = name.lowercase()

        if (isBlacklisted(name)) {
            Log.d(TAG, "Blacklisted: $name")
            return Int.MAX_VALUE
        }

        return when {
            // Hardware OMX decoders (prefer in order)
            lower.contains("amlogic") -> 0
            lower.contains("allwinner") -> 1
            lower.contains("omx.") && !lower.contains("google") -> 5
            // Codec2 hardware (c2.<vendor>.avc)
            lower.contains("c2.") && !lower.contains("android") -> 10
            // Codec2 generic (c2.android.avc) — hardware-backed on most devices
            lower.contains("c2.android") -> 15
            // Software codecs (last resort)
            lower.contains("omx.google") -> 50
            else -> 30
        }
    }

    // ── Selection ────────────────────────────────────────────────────

    fun selectDecoder(mime: String, format: MediaFormat, surface: Surface?): MediaCodec? {
        val candidates = getAllDecoders(mime)
        if (candidates.isEmpty()) {
            Log.e(TAG, "No decoders found for $mime")
            return null
        }

        val sorted = candidates.sortedBy { it.priority }

        Log.d(TAG, "Platform: $chip, Android $sdkInt")
        Log.d(TAG, "Candidates (${sorted.size}):")

        for (info in sorted) {
            Log.d(TAG, "  ${info.priority}: ${info.name} ${info.notes}")
        }

        for (info in sorted) {
            try {
                Log.d(TAG, "Trying: ${info.name}")
                val codec = MediaCodec.createByCodecName(info.name)
                codec.configure(format, surface, null, 0)
                codec.start()
                Log.d(TAG, "Selected: ${info.name}")
                return codec
            } catch (e: Exception) {
                Log.w(TAG, "Failed: ${info.name} — ${e.message}")
            }
        }

        // System default fallback
        try {
            Log.d(TAG, "Trying system default decoder")
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, surface, null, 0)
            codec.start()
            Log.d(TAG, "Selected system default: ${codec.codecInfo.name}")
            return codec
        } catch (e: Exception) {
            Log.e(TAG, "All decoders failed: ${e.message}")
            return null
        }
    }

    private fun getAllDecoders(mime: String): List<DecoderInfo> {
        val result = mutableListOf<DecoderInfo>()
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                if (mime !in info.supportedTypes) continue
                val p = priority(info.name)
                val note = if (isBlacklisted(info.name)) " [BLACKLISTED]" else ""
                result.add(DecoderInfo(info.name, p, note))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate decoders", e)
        }
        return result
    }
}
