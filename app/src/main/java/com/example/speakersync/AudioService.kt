package com.example.speakersync

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.IBinder
import android.os.Process
import java.nio.ByteOrder

class AudioService : Service() {
    @Volatile private var gen = 0
    private var proj: MediaProjection? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { gen++; proj?.stop(); super.onDestroy() }

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        intent ?: return START_NOT_STICKY
        val my = ++gen
        val mirror = intent.getBooleanExtra("mirror", false)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("sync", "Speaker Sync", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "sync").setContentTitle("Speaker Sync running")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n, if (mirror) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                              else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        val ids = intent.getIntArrayExtra("ids") ?: return START_NOT_STICKY
        val roles = intent.getIntArrayExtra("roles")!!
        val delays = intent.getIntArrayExtra("delays")!!
        val echo = intent.getBooleanExtra("echo", false)
        val devs = (getSystemService(AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val sinks = ids.indices.mapNotNull { j -> devs.firstOrNull { it.id == ids[j] }?.let { Sink(it, roles[j], delays[j]) } }
        if (sinks.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val alive = { gen == my }

        if (mirror) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            proj = mpm.getMediaProjection(intent.getIntExtra("code", 0), intent.getParcelableExtra("proj")!!)
            proj!!.registerCallback(object : MediaProjection.Callback() {}, null)
            Thread { try { runMirror(sinks, echo, alive) } catch (e: Exception) { e.printStackTrace() } }.start()
        } else {
            val uri = intent.data!!
            Thread {
                try { runPlayer(sinks, uri, echo, alive) } catch (e: Exception) { e.printStackTrace() }
                if (alive()) stopSelf()
            }.start()
        }
        return START_NOT_STICKY
    }

    private fun runMirror(sinks: List<Sink>, echo: Boolean, alive: () -> Boolean) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val buf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4
        val rec = AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(fmt).setBufferSizeInBytes(buf).build()
        val eng = Engine(sinks, 44100, echo)
        eng.begin(); rec.startRecording()
        val b = ShortArray(2048)
        while (alive()) { val n = rec.read(b, 0, b.size); if (n > 0) eng.push(b, n) }
        rec.stop(); rec.release(); eng.end()
    }

    private fun runPlayer(sinks: List<Sink>, uri: Uri, echo: Boolean, alive: () -> Boolean) {
        val ex = MediaExtractor(); ex.setDataSource(this, uri, null)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        ex.selectTrack(ti)
        val f = ex.getTrackFormat(ti)
        val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(f, null, null, 0); codec.start()
        val info = MediaCodec.BufferInfo()
        var eos = false; var ch = 2; var eng: Engine? = null
        while (alive()) {
            if (!eos) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                eng = Engine(sinks, of.getInteger(MediaFormat.KEY_SAMPLE_RATE), echo).also { it.begin() }
            } else if (o >= 0) {
                val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                val arr = ShortArray(sb.remaining()); sb.get(arr)
                val frames = arr.size / ch
                val st = ShortArray(frames * 2)
                for (k in 0 until frames) { st[2 * k] = arr[k * ch]; st[2 * k + 1] = if (ch > 1) arr[k * ch + 1] else arr[k * ch] }
                eng?.push(st, st.size)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); ex.release(); eng?.end()
    }
}
