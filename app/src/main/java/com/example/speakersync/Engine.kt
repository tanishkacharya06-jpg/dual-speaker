package com.example.speakersync

import android.media.*

/** role: 0 = Main (stereo), 1 = Left, 2 = Right, 3 = Subwoofer */
class Sink(val device: AudioDeviceInfo, val role: Int, val delayMs: Int)

class Engine(private val sinks: List<Sink>, private val rate: Int, private val echo: Boolean) {
    private val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private val tracks = sinks.map { s ->
        AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
            .setBufferSizeInBytes(rate * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
            .also { it.preferredDevice = s.device }
    }
    private val lp = FloatArray(sinks.size)
    private val echoLen = (rate * 0.25).toInt() * 2
    private val echoBuf = sinks.map { FloatArray(echoLen) }
    private val echoPos = IntArray(sinks.size)
    private val alpha = (2 * Math.PI * 120 / rate).let { (it / (it + 1)).toFloat() }

    fun begin() = tracks.forEachIndexed { i, t ->
        t.play()
        val n = rate * sinks[i].delayMs / 1000 * 2
        if (n > 0) t.write(ShortArray(n), 0, n)
    }

    fun push(inp: ShortArray, len: Int) {
        for (i in sinks.indices) {
            val out = ShortArray(len)
            val role = sinks[i].role
            var k = 0
            while (k + 1 < len) {
                val l = inp[k].toFloat(); val r = inp[k + 1].toFloat()
                var a: Float; var b: Float
                when (role) {
                    1 -> { a = l; b = l }
                    2 -> { a = r; b = r }
                    3 -> { lp[i] += alpha * ((l + r) / 2f - lp[i]); a = lp[i] * 1.5f; b = a }
                    else -> { a = l; b = r }
                }
                if (echo) {
                    val p = echoPos[i]; val eb = echoBuf[i]
                    a += 0.4f * eb[p]; b += 0.4f * eb[p + 1]
                    eb[p] = a; eb[p + 1] = b
                    echoPos[i] = (p + 2) % echoLen
                }
                out[k] = a.coerceIn(-32768f, 32767f).toInt().toShort()
                out[k + 1] = b.coerceIn(-32768f, 32767f).toInt().toShort()
                k += 2
            }
            tracks[i].write(out, 0, len)
        }
    }

    fun end() = tracks.forEach { try { it.stop() } catch (_: Exception) {}; it.release() }
}
